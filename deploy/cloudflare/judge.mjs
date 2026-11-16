import { DurableObject } from "cloudflare:workers";
import { isReady, judgeEnvironment } from "./runtime.mjs";

const PORT = 8080;
const IDLE_MS = 5 * 60_000;
const BOOT_MS = 8 * 60_000;
const SHUTDOWN_MS = 20_000;

export class CobwebJudge extends DurableObject {
  async tick() {
    // Cron may retry. Share startups, and keep a single instance for the queue.
    this.pending ??= this.keepRunning().finally(() => { this.pending = undefined; });
    await this.pending;
  }

  async keepRunning() {
    const container = this.ctx.container;
    const version = this.env.DEPLOYMENT?.id ?? "local";
    const started = await this.ctx.storage.get("startup");
    if (container.running && started?.version !== version) {
      container.signal(15);
      const deadline = Date.now() + SHUTDOWN_MS;
      while (container.running && Date.now() < deadline) await scheduler.wait(500);
      if (container.running) await container.destroy();
    }

    if (!container.running) {
      const env = judgeEnvironment(this.env);
      await this.ctx.storage.put("startup", { version, at: Date.now() });
      container.start({
        image: container.images.app,
        instance: "standard-3",
        enableInternet: true,
        env,
      });
      this.ctx.waitUntil(container.monitor().catch(() => console.error("Cobweb judge process stopped unexpectedly.")));
      await container.setInactivityTimeout(IDLE_MS);
      return;
    }

    await container.setInactivityTimeout(IDLE_MS);
    if (!await isReady(container.getTcpPort(PORT)) && Date.now() - started.at >= BOOT_MS) {
      console.error("Cobweb judge readiness timed out. Cron will retry.");
      await container.destroy();
    }
  }
}

export default {
  async scheduled(_controller, env) {
    await env.JUDGE.getByName("judge").tick();
  },
};
