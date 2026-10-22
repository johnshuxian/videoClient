import { register } from 'node:module';

// 通过 Node 的正式注册入口加载 DevEco 兼容钩子，避免实验性 loader 参数的警告。
register(new URL('./force-deveco-commonjs.mjs', import.meta.url).href, import.meta.url);
