import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.shuxian.videoclient",
  appName: "影窗",
  webDir: "dist",
  android: {
    allowMixedContent: true,
  },
};

export default config;
