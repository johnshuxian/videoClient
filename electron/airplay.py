"""AirPlay video bridge for the Electron process.

The bridge stays alive while a video plays because AirPlay keeps its control
connection open for the whole playback session. Requests and replies are JSON
lines on stdin/stdout; diagnostics go to stderr.
"""

import asyncio
import json
import sys

import pyatv
from pyatv.const import PairingRequirement, Protocol
from pyatv.protocols.airplay.utils import AirPlayFlags, parse_features
from pyatv.support.http import decode_bplist_from_body


credentials = {}

devices = {}
pairing = None
pairing_id = None
player = None
play_task = None


def reply(request_id, result=None, error=None):
    print(json.dumps({"requestId": request_id, "result": result, "error": error}), flush=True)


async def stop_playback():
    global player, play_task
    try:
        if player is not None and play_task is not None and not play_task.done():
            # FacadeStream 没有 stop；AirPlay 的遥控接口会结束电视端播放。
            await player.remote_control.stop()
    finally:
        if play_task is not None:
            if not play_task.done():
                play_task.cancel()
            await asyncio.gather(play_task, return_exceptions=True)
            play_task = None
        if player is not None:
            await asyncio.gather(*player.close(), return_exceptions=True)
            player = None


async def handle(command):
    global credentials, devices, pairing, pairing_id, player, play_task
    action = command["action"]

    if action == "scan":
        # Electron 从 SQLite 读取配对信息，通过进程管道交给助手，不再写独立 JSON 文件。
        credentials = command.get("credentials", {})
        found = await pyatv.scan(asyncio.get_running_loop(), timeout=3, protocol=Protocol.AirPlay)
        devices = {}
        result = []
        for device in found:
            service = device.get_service(Protocol.AirPlay)
            if service is None:
                continue
            flags = parse_features(service.properties.get("features", "0x0"))
            if not flags & (
                AirPlayFlags.SupportsAirPlayVideoV1
                | AirPlayFlags.SupportsAirPlayVideoV2
                | AirPlayFlags.SupportsAirPlayVideoPlayQueue
            ):
                continue
            identifier = device.identifier or str(device.address)
            devices[identifier] = device
            result.append(
                {
                    "id": identifier,
                    "name": device.name,
                    "host": str(device.address),
                    "type": "airplay",
                    "paired": bool(credentials.get(identifier))
                    or service.pairing != PairingRequirement.Mandatory,
                }
            )
        return result

    if action == "pair_begin":
        device = devices[command["id"]]
        if pairing is not None:
            await pairing.close()
        pairing = await pyatv.pair(device, Protocol.AirPlay, asyncio.get_running_loop())
        pairing_id = command["id"]
        await pairing.begin()
        return {"pinRequired": pairing.device_provides_pin}

    if action == "pair_finish":
        if pairing is None:
            raise RuntimeError("请先开始 AirPlay 配对")
        try:
            pairing.pin(int(command["pin"]))
            await pairing.finish()
            if not pairing.has_paired:
                raise RuntimeError("AirPlay 配对失败")
            credentials[pairing_id] = pairing.service.credentials
            return {"id": pairing_id, "credentials": credentials[pairing_id]}
        finally:
            await pairing.close()
            pairing = None
            pairing_id = None

    if action == "play":
        await stop_playback()
        device = devices[command["id"]]
        stored = credentials.get(command["id"])
        if stored:
            device.set_credentials(Protocol.AirPlay, stored)
        player = await pyatv.connect(
            device, asyncio.get_running_loop(), protocol=Protocol.AirPlay
        )
        play_task = asyncio.create_task(player.stream.play_url(command["url"]))
        # play_url runs until the receiver ends playback. Catch immediate pairing
        # and protocol errors while keeping the session alive after this reply.
        done, _ = await asyncio.wait([play_task], timeout=3)
        if done:
            await play_task
            raise RuntimeError("接收设备没有开始播放")

        def playback_finished(task):
            if task.cancelled():
                return
            try:
                task.result()
            except Exception as error:
                event = {"event": "error", "sessionId": command["sessionId"], "error": str(error)}
            else:
                event = {"event": "ended", "sessionId": command["sessionId"]}
            print(json.dumps(event), flush=True)

        play_task.add_done_callback(playback_finished)
        return True

    if action == "stop":
        await stop_playback()
        return True

    if action == "progress":
        if player is None or play_task is None or play_task.done():
            return None
        # pyatv 的 AirPlay 接口未公开进度；读取它播放时使用的连接上的播放信息。
        stream = player.stream.get(Protocol.AirPlay)
        connection = stream._connection
        if connection is None:
            return None
        response = await connection.get("/playback-info")
        info = decode_bplist_from_body(response) if response.body else {}
        position = info.get("position")
        duration = info.get("duration")
        if not isinstance(position, (int, float)) or not isinstance(duration, (int, float)) or duration <= 0:
            return None
        return {"position": max(0, position), "duration": duration}

    raise ValueError(f"未知 AirPlay 操作：{action}")


async def main():
    try:
        while line := await asyncio.to_thread(sys.stdin.readline):
            command = json.loads(line)
            try:
                reply(command["requestId"], await handle(command))
            except Exception as error:
                reply(command["requestId"], error=str(error))
    finally:
        await stop_playback()
        if pairing is not None:
            await pairing.close()


if __name__ == "__main__":
    asyncio.run(main())
