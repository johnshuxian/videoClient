import { connection } from '@kit.NetworkKit';

export interface LanNetwork {
  handle: connection.NetHandle;
  address: string;
}

export async function lanNetwork(): Promise<LanNetwork> {
  // 优先选择 Wi-Fi/有线网；移动数据或 VPN 即使是默认网络也无法到达电视。
  for (const handle of await connection.getAllNets()) {
    const types = (await connection.getNetCapabilities(handle)).bearerTypes;
    if (!types.includes(connection.NetBearType.BEARER_WIFI) &&
        !types.includes(connection.NetBearType.BEARER_ETHERNET)) continue;
    const properties = await connection.getConnectionProperties(handle);
    const ipv4 = properties.linkAddresses.find((entry) => entry.address.address.includes('.') &&
      !entry.address.address.startsWith('127.'));
    if (ipv4) return { handle, address: ipv4.address.address };
  }
  throw new Error('请连接与电视相同的 Wi-Fi 或有线网络');
}
