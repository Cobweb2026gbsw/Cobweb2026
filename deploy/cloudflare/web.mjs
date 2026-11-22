import { DurableObject } from "cloudflare:workers";
import { appRequest, probeReadiness, webEnvironment } from "./runtime.mjs";

const PORT = 8080;
const IDLE_MS = 5 * 60_000;

export class CobwebWeb extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    if (ctx.container?.running) {
      void ctx.blockConcurrencyWhile(() => ctx.container.setInactivityTimeout(IDLE_MS));
    }
  }

  async fetch(request) {
    const container = this.ctx.container;
    try {
      if (!container || !this.ready || !container.running) {
        this.starting ??= this.startAndWait(container).finally(() => { this.starting = undefined; });
        await this.starting;
      }
      // Return the upstream response intact so browser cookies and OAuth redirects survive.
      return await container.getTcpPort(PORT).fetch(appRequest(request, this.env.PUBLIC_BASE_URL));
    } catch {
      this.ready = false;
      return new Response("Cobweb is temporarily unavailable.", {
        status: 503,
        headers: { "Cache-Control": "no-store", "Retry-After": "5" },
      });
    }
  }

  async startAndWait(container) {
    if (!container.running) {
      container.start({
        image: container.images.app,
        instance: { vcpu: 1, memoryMib: 3072, diskMb: 4000 },
        enableInternet: true,
        env: webEnvironment(this.env),
      });
    }
    await container.setInactivityTimeout(IDLE_MS);

    const startedWaiting = Date.now();
    const deadline = startedWaiting + 60_000;
    const port = container.getTcpPort(PORT);
    let lastHttpStatus = null;
    while (Date.now() < deadline) {
      const probe = await probeReadiness(port);
      if (probe.ready) {
        this.ready = true;
        return;
      }
      lastHttpStatus = probe.status ?? lastHttpStatus;
      await scheduler.wait(500);
    }
    console.error("Cobweb web readiness timed out.", { lastHttpStatus, waitedMs: Date.now() - startedWaiting });
    throw new Error("Cobweb web is not ready");
  }
}

export default {
  async fetch(request, env) {
    // Each Worker release starts with its own image and secrets. Old web instances age out.
    const version = env.DEPLOYMENT?.id ?? "local";
    const upstream = await env.WEB.getByName(`web-${version}`).fetch(request);
    if (new URL(request.url).pathname !== "/api/ready" || !env.DEPLOYMENT?.tag) return upstream;
    const response = new Response(upstream.body, upstream);
    response.headers.set("X-Cobweb-Release", env.DEPLOYMENT.tag);
    response.headers.set("Cache-Control", "no-store");
    return response;
  },
};
