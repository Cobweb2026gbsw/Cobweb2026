import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({command}) => ({
  plugins: [react()],
  // 개발 라우터는 /, JAR에 포함되는 운영 자산만 /ui/ 아래에 둡니다.
  base: command === 'build' ? '/ui/' : '/',
  build: { outDir: '../build/generated-frontend/static/ui', emptyOutDir: true },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8080', '/markdown.js': 'http://127.0.0.1:8080', '/code-editor.js': 'http://127.0.0.1:8080', '/react-bridge.js': 'http://127.0.0.1:8080', '/vendor': 'http://127.0.0.1:8080' },
  },
}));
