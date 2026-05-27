import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {defineConfig, loadEnv} from 'vite';
import cesium from 'vite-plugin-cesium';

export default defineConfig(({mode}) => {
  const env = loadEnv(mode, '.', '');
  return {
    plugins: [react(), tailwindcss(), cesium()],
    define: {
      'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY),
      // sockjs-client（STOMP WebSocket）在浏览器中依赖 Node 的 global
      global: 'globalThis',
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
      },
    },
    server: {
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modifyâfile watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // 浏览器从 localhost:3000 直连 localhost:8000 的 TiTiler 瓦片常遇跨域；开发时走同源代理可加 CORS
      proxy: {
        '/titiler-proxy': {
          target: env.VITE_TITILER_PROXY_TARGET || env.VITE_TITILER_URL || 'http://localhost:8000',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/titiler-proxy/, '') || '/',
        },
        '/ws': {
          target: env.VITE_RASTER_API_URL || 'http://localhost:8080',
          changeOrigin: true,
          ws: true,
        },
      },
    },
  };
});
