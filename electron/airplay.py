"""AirPlay video bridge for the Electron process.

The bridge stays alive while a video plays because AirPlay keeps its control
connection open for the whole playback session. Requests and replies are JSON
lines on stdin/stdout; diagnostics go to stderr.
"""

import asyncio
import json
import os
import sys

import pyatv
from pyatv.const import PairingRequirement, Protocol
from pyatv.protocols.airplay.utils import AirPlayFlags, parse_features


credentials_path = sys.argv[1]
try:
    with open(credentials_path, encoding="utf-8") as file:
        credentials = json.load(file)
except FileNotFoundError:
    credentials = {}

devices = {}
pairing = None
pairing_id = None
player = None
play_task = None


def reply(request_id, result=None, error=None):
    print(json.dumps({"id": request_id, "result": result, "error": error}), flush=True)


async def stop_playback():
    global player, play_task
    if player is not None:
        # AirPlay's stream stop closes the session; the background play_url call
        # then finishes and can be cancelled without leaving a receiver active.
        player.stream.stop()
    if play_task is not None:
        play_task.cancel()
        await asyncio.gather(play_task, return_exceptions=True)
        play_task = None
    if player is not None:
        await asyncio.gather(*player.close(), return_exceptions=True)
        player = None


async def handle(command):
    global devices, pairing, pairing_id, player, play_task
    action = command["action"]

    if action == "scan":
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
            os.makedirs(os.path.dirname(credentials_path), exist_ok=True)
            with open(credentials_path, "w", encoding="utf-8") as file:
                json.dump(credentials, file)
            if os.name != "nt":
                os.chmod(credentials_path, 0o600)
            return True
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
        return True

    if action == "stop":
        await stop_playback()
        return True

    raise ValueError(f"未知 AirPlay 操作：{action}")


async def main():
    try:
        while line := await asyncio.to_thread(sys.stdin.readline):
            command = json.loads(line)
            try:
                reply(command["id"], await handle(command))
            except Exception as error:
                reply(command["id"], error=str(error))
    finally:
        await stop_playback()
        if pairing is not None:
            await pairing.close()


if __name__ == "__main__":
    asyncio.run(main())
