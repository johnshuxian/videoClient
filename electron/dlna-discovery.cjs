const dgram = require("node:dgram");
const os = require("node:os");
const { setTimeout: delay } = require("node:timers/promises");

const targets = [
  "urn:schemas-upnp-org:device:MediaRenderer:1",
  "urn:schemas-upnp-org:service:AVTransport:1",
  "ssdp:all",
];

async function discoverDlnaOnInterfaces() {
  const addresses = Object.values(os.networkInterfaces())
    .flatMap((entries) => entries || [])
    .filter((entry) => entry.family === "IPv4" && !entry.internal)
    .map((entry) => entry.address);
  const locations = new Map();

  await Promise.all(
    addresses.map(async (address) => {
      const socket = dgram.createSocket("udp4");
      socket.on("message", (message) => {
        if (!/^HTTP\/1\.[01] 200\b/i.test(message.toString())) return;
        const location = message.toString().match(/^location:\s*(\S+)/im)?.[1];
        if (location && locations.size < 30 && !locations.has(location))
          locations.set(location, address);
      });
      socket.on("error", (error) =>
        console.warn(`DLNA 网卡 ${address} 扫描失败：`, error),
      );
      try {
        await new Promise((resolve, reject) => {
          socket.once("error", reject);
          socket.bind(0, address, () => {
            socket.off("error", reject);
            resolve();
          });
        });
        // 每张网卡使用自己的源地址，避免组播默认发往 VPN 或其他接口。
        socket.setMulticastInterface(address);
        for (let burst = 0; burst < 3; burst++) {
          await Promise.all(
            targets.map((target) => {
              const data = Buffer.from(
                `M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ${target}\r\n\r\n`,
              );
              return new Promise((resolve, reject) =>
                socket.send(data, 1900, "239.255.255.250", (error) =>
                  error ? reject(error) : resolve(),
                ),
              );
            }),
          );
          if (burst < 2) await delay(1000);
        }
        await delay(1500);
      } catch (error) {
        console.warn(`DLNA 网卡 ${address} 扫描失败：`, error);
      } finally {
        try {
          socket.close();
        } catch {
          // bind 失败时 socket 尚未启动。
        }
      }
    }),
  );

  const found = await Promise.all(
    [...locations].map(async ([location, localAddress]) => {
      try {
        const url = new URL(location);
        if (!["http:", "https:"].includes(url.protocol)) return null;
        const response = await fetch(url, {
          signal: AbortSignal.timeout(1800),
        });
        if (!response.ok) return null;
        const xml = await response.text();
        const service = [
          ...xml.matchAll(/<(?:\w+:)?service>([\s\S]*?)<\/(?:\w+:)?service>/gi),
        ]
          .map((match) => match[1])
          .find((block) =>
            /<(?:\w+:)?serviceType>\s*urn:schemas-upnp-org:service:AVTransport:\d+\s*<\/(?:\w+:)?serviceType>/i.test(
              block,
            ),
          );
        const controlPath = service?.match(
          /<(?:\w+:)?controlURL>([^<]+)<\/(?:\w+:)?controlURL>/i,
        )?.[1];
        if (!controlPath) return null;
        const base = xml.match(
          /<(?:\w+:)?URLBase>([^<]+)<\/(?:\w+:)?URLBase>/i,
        )?.[1];
        const controlUrl = new URL(
          controlPath.replaceAll("&amp;", "&"),
          base ? new URL(base, url) : url,
        ).href;
        const name =
          xml.match(
            /<(?:\w+:)?friendlyName>([^<]+)<\/(?:\w+:)?friendlyName>/i,
          )?.[1] || url.hostname;
        return {
          id: location,
          name,
          host: url.hostname,
          localAddress,
          location,
          controlUrl,
        };
      } catch (error) {
        console.warn(`DLNA 设备描述读取失败：${location}`, error);
        return null;
      }
    }),
  );
  return found.filter(Boolean);
}

module.exports = { discoverDlnaOnInterfaces };
