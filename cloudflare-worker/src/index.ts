import puppeteer, { type BrowserWorker } from "@cloudflare/puppeteer";

export interface Env {
  BROWSER?: BrowserWorker;
  WORKER_AUTH_TOKEN?: string;
}

interface ColumnPayload {
  index: number;
  predictions: string[]; // 15 items: "1", "0"/"X", or "2"
}

interface NesineAutoPlayRequest {
  username: string;
  password: string;
  couponTitle?: string;
  totalCostTL?: number;
  dryRun?: boolean;
  columns: ColumnPayload[];
}

type AutoPlayStatus =
  | "COMPLETED"
  | "NO_BULLETIN"
  | "INSUFFICIENT_BALANCE"
  | "REQUIRES_2FA"
  | "WAF_BLOCKED"
  | "LOGIN_FAILED"
  | "ERROR";

interface NesineAutoPlayResponse {
  success: boolean;
  status: AutoPlayStatus;
  sessionId: string;
  playedColumnsCount: number;
  steps: string[];
  screenshotBase64?: string;
  errorMessage?: string;
}

const NESINE_SPORTOTO_URL = "https://www.nesine.com/sportoto";
const DESKTOP_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(),
      });
    }

    const url = new URL(request.url);
    const configuredSecret = (env.WORKER_AUTH_TOKEN ?? "").trim();

    if (configuredSecret.length > 0 && !isAuthorized(request, configuredSecret)) {
      return jsonResponse<NesineAutoPlayResponse>(
        {
          success: false,
          status: "ERROR",
          sessionId: "",
          playedColumnsCount: 0,
          steps: ["HTTP 401: Worker erişim anahtarı eşleşmiyor."],
          errorMessage: "WORKER_AUTH_TOKEN eşleşmiyor. Uygulama ayarlarından doğru Worker token'ını girin.",
        },
        401
      );
    }

    // Fast readiness check endpoint (used by Android "Bağlantıyı Test Et")
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
      const browserReady = Boolean(env.BROWSER);
      return jsonResponse(
        {
          ok: browserReady,
          worker: "nesine-browser-runner",
          version: "2.0.0",
          browserBindingReady: browserReady,
          authEnabled: configuredSecret.length > 0,
          message: browserReady
            ? "Cloudflare Worker ve BROWSER binding hazır."
            : "Worker çalışıyor ancak Settings > Bindings altında BROWSER binding eksik.",
        },
        browserReady ? 200 : 500
      );
    }

    if (request.method !== "POST" || (url.pathname !== "/" && url.pathname !== "/autoplay")) {
      return jsonResponse<NesineAutoPlayResponse>(
        {
          success: false,
          status: "ERROR",
          sessionId: "",
          playedColumnsCount: 0,
          steps: [`HTTP 405: ${request.method} ${url.pathname} desteklenmiyor.`],
          errorMessage: "Bu Worker'da otomatik oynama için POST / ve bağlantı testi için GET /health kullanılır.",
        },
        request.method !== "POST" ? 405 : 404
      );
    }

    let payload: NesineAutoPlayRequest;
    try {
      payload = await request.json<NesineAutoPlayRequest>();
    } catch {
      return jsonResponse<NesineAutoPlayResponse>(
        {
          success: false,
          status: "ERROR",
          sessionId: "",
          playedColumnsCount: 0,
          steps: ["HTTP 400: Geçersiz JSON isteği."],
          errorMessage: "Geçersiz JSON gövdesi.",
        },
        400
      );
    }

    if (!payload.username?.trim() || !payload.password || !Array.isArray(payload.columns) || payload.columns.length === 0) {
      return jsonResponse<NesineAutoPlayResponse>(
        {
          success: false,
          status: "ERROR",
          sessionId: "",
          playedColumnsCount: 0,
          steps: ["HTTP 400: Kullanıcı adı, şifre veya kolon verisi eksik."],
          errorMessage: "Nesine kullanıcı adı, şifre ve en az 1 kolon gereklidir.",
        },
        400
      );
    }

    if (!env.BROWSER) {
      return jsonResponse<NesineAutoPlayResponse>(
        {
          success: false,
          status: "ERROR",
          sessionId: "",
          playedColumnsCount: 0,
          steps: ["HTTP 500: Worker'da Browser Rendering binding ('BROWSER') tanımlı değil."],
          errorMessage: "Cloudflare Dashboard > Settings > Bindings bölümünden 'BROWSER' adıyla Browser Rendering bağlayın.",
        },
        500
      );
    }

    const sessionId = crypto.randomUUID();
    const steps: string[] = [];
    const totalCostTL = Number(payload.totalCostTL ?? 0);
    const dryRun = payload.dryRun ?? true;
    let browser: puppeteer.Browser | undefined;

    try {
      steps.push("1/5 Cloudflare Browser Rendering oturumu başlatılıyor...");
      browser = await withTimeout(
        puppeteer.launch(env.BROWSER),
        15000,
        "Cloudflare Browser Rendering oturumu 15 sn içinde açılamadı."
      );

      const page = await browser.newPage();
      await page.setViewport({ width: 1280, height: 900 });
      await page.setUserAgent(DESKTOP_USER_AGENT);

      steps.push("2/5 nesine.com/sportoto sayfası açılıyor...");
      await page.goto(NESINE_SPORTOTO_URL, {
        waitUntil: "domcontentloaded",
        timeout: 15000,
      });

      await page
        .waitForFunction(
          () => {
            const text = (document.body?.innerText ?? "").toLowerCase();
            return (
              !!document.getElementById("m-c-0-0-0") ||
              !!document.querySelector("input[type='password']") ||
              text.includes("aktif spor toto programı bulunmamaktadır") ||
              text.includes("aktif spor toto programi bulunmamaktadir") ||
              text.includes("checking your browser") ||
              text.includes("just a moment")
            );
          },
          { timeout: 4500 }
        )
        .catch(() => undefined);

      const pageTitle = await page.title().catch(() => "nesine.com/sportoto");
      steps.push(`Sayfa yüklendi: ${pageTitle}`);

      // Check for WAF / Challenge
      const initialText = await page.evaluate(() => document.body?.innerText ?? "");
      if (
        initialText.includes("Checking your browser") ||
        initialText.includes("Just a moment") ||
        initialText.includes("Access Denied")
      ) {
        const shot = await screenshotSafe(page);
        return jsonResponse<NesineAutoPlayResponse>({
          success: false,
          status: "WAF_BLOCKED",
          sessionId,
          playedColumnsCount: 0,
          steps: [...steps, "⚠️ Nesine WAF koruması bulut IP'yi engelledi. Uygulama içi WebView robotuyla devam edebilirsiniz."],
          screenshotBase64: shot,
          errorMessage: "Nesine.com bulut tarayıcıya bot doğrulaması (Challenge) uyguladı.",
        });
      }

      // 3. Login check & safe form fill
      steps.push(`3/5 '${payload.username}' hesabı ile giriş kontrol ediliyor...`);
      const modalClicked = await page.evaluate(() => {
        const hasPassword = !!document.querySelector("input[type='password']");
        if (hasPassword) return false;
        const modalBtn = document.querySelector<HTMLElement>(
          "[data-testid='login-button'], button[class*='login'], button.login-btn, .header-login-btn"
        );
        if (modalBtn) {
          modalBtn.click();
          return true;
        }
        return false;
      });

      if (modalClicked) {
        await sleep(700);
      }

      const loginSubmitted = await page.evaluate(
        (user: string, pass: string) => {
          const userInput = document.querySelector<HTMLInputElement>(
            "input[name='username'], input[name='Username'], input[id*='user' i], input[placeholder*='Üye'], input[placeholder*='TC']"
          );
          const passInput = document.querySelector<HTMLInputElement>(
            "input[name='password'], input[name='Password'], input[type='password']"
          );
          if (!userInput || !passInput) return false;

          const setNativeValue = (el: HTMLInputElement, val: string) => {
            const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
            if (setter) setter.call(el, val);
            else el.value = val;
            el.dispatchEvent(new Event("input", { bubbles: true }));
            el.dispatchEvent(new Event("change", { bubbles: true }));
          };

          userInput.focus();
          setNativeValue(userInput, user);
          passInput.focus();
          setNativeValue(passInput, pass);

          const submit = document.querySelector<HTMLElement>(
            "button[type='submit'], input[type='submit'], button[class*='submit']"
          );
          submit?.click();
          return true;
        },
        payload.username.trim(),
        payload.password
      );

      if (loginSubmitted) {
        await sleep(2200);
        const postLoginText = (await page.evaluate(() => document.body?.innerText ?? "")).toLowerCase();
        if (
          postLoginText.includes("sms şifresi") ||
          postLoginText.includes("doğrulama kodu") ||
          postLoginText.includes("tek kullanımlık") ||
          postLoginText.includes("otp")
        ) {
          const shot = await screenshotSafe(page);
          return jsonResponse<NesineAutoPlayResponse>({
            success: false,
            status: "REQUIRES_2FA",
            sessionId,
            playedColumnsCount: 0,
            steps: [...steps, "📲 Nesine SMS doğrulama (2FA) kodu istedi."],
            screenshotBase64: shot,
            errorMessage: "Nesine SMS doğrulaması istedi. Uygulama içi WebView robotu üzerinden SMS kodunu girerek oynayabilirsiniz.",
          });
        }

        if (
          postLoginText.includes("hatalı şifre") ||
          postLoginText.includes("şifreniz hatalı") ||
          postLoginText.includes("üye numaranız veya şifreniz") ||
          postLoginText.includes("kullanıcı adı veya şifre")
        ) {
          const shot = await screenshotSafe(page);
          return jsonResponse<NesineAutoPlayResponse>({
            success: false,
            status: "LOGIN_FAILED",
            sessionId,
            playedColumnsCount: 0,
            steps: [...steps, "❌ Nesine kullanıcı adı veya şifre hatalı."],
            screenshotBase64: shot,
            errorMessage: "Nesine girişi reddedildi: kullanıcı adı veya şifre hatalı.",
          });
        }
      }

      // 4. Verify active 15-match Spor Toto bulletin
      steps.push("4/5 Aktif Spor Toto bülteni doğrulanıyor...");
      const bulletinState = await page.evaluate(() => {
        const text = (document.body?.innerText ?? "").toLowerCase();
        const hasNativeGrid =
          !!document.getElementById("m-c-0-0-0") &&
          !!document.getElementById("m-c-14-0-0");
        const fallbackRows = document.querySelectorAll(
          ".sportoto-row, [data-match-row], tr[data-match-no], .toto-match-item"
        );
        const explicitlyClosed =
          text.includes("aktif spor toto programı bulunmamaktadır") ||
          text.includes("aktif spor toto programi bulunmamaktadir") ||
          text.includes("yeni bülten") ||
          text.includes("bülten bulunm") ||
          text.includes("aktif bülten yok") ||
          text.includes("oynanacak bülten") ||
          text.includes("program açıklanmadı");

        return {
          playable: (hasNativeGrid || fallbackRows.length >= 15) && !explicitlyClosed,
          hasNativeGrid,
          rowCount: fallbackRows.length,
        };
      });

      if (!bulletinState.playable) {
        const shot = await screenshotSafe(page);
        return jsonResponse<NesineAutoPlayResponse>({
          success: false,
          status: "NO_BULLETIN",
          sessionId,
          playedColumnsCount: 0,
          steps: [...steps, "❌ Nesine'de şu an oynanacak aktif 15 maçlık Spor Toto bülteni yok."],
          screenshotBase64: shot,
          errorMessage: "Yeni bülten yüklenmedi",
        });
      }

      // 5. Mark coupon columns in batches of 4 (#m-c-{match}-{group}-{outcome})
      steps.push(`5/5 ${payload.columns.length} kolon tarayıcıda işaretleniyor...`);
      let playedCount = 0;
      const chunkSize = 4;

      for (let start = 0; start < payload.columns.length; start += chunkSize) {
        const batchCols = payload.columns.slice(start, start + chunkSize);
        const batchMarked = await page.evaluate((cols: ColumnPayload[]) => {
          const hasNativeGrid = !!document.getElementById("m-c-0-0-0");
          if (hasNativeGrid) {
            // Reset all 4 slots before marking batch
            for (let g = 0; g < 4; g++) {
              for (let m = 0; m < 15; m++) {
                for (let o = 0; o < 3; o++) {
                  const box = document.getElementById(`m-c-${m}-${g}-${o}`) as HTMLInputElement | null;
                  if (box && box.checked) {
                    box.click();
                  }
                }
              }
            }

            let markedCols = 0;
            cols.forEach((col, g) => {
              let markedMatches = 0;
              for (let m = 0; m < 15 && m < col.predictions.length; m++) {
                const raw = (col.predictions[m] ?? "").trim().toUpperCase();
                const outcomeIdx = raw === "1" ? 0 : raw === "X" || raw === "0" ? 1 : 2;
                const box = document.getElementById(`m-c-${m}-${g}-${outcomeIdx}`) as HTMLInputElement | null;
                if (box) {
                  if (!box.checked) {
                    box.click();
                  }
                  if (!box.checked) {
                    box.checked = true;
                    box.dispatchEvent(new Event("change", { bubbles: true }));
                  }
                  if (box.checked) {
                    markedMatches++;
                  }
                }
              }
              if (markedMatches === 15) {
                markedCols++;
              }
            });
            return markedCols;
          }

          // Fallback row layout
          const rows = document.querySelectorAll(
            ".sportoto-row, [data-match-row], tr[data-match-no], .toto-match-item"
          );
          if (rows.length < 15 || !cols[0]) return 0;

          for (let i = 0; i < 15; i++) {
            const pick = (cols[0].predictions[i] ?? "").trim().toUpperCase();
            const normalized = pick === "0" ? "X" : pick === "X" ? "0" : pick;
            const buttons = rows[i].querySelectorAll<HTMLElement>("button, .outcome-btn, td[data-outcome], label");
            buttons.forEach((btn) => {
              const txt = btn.innerText.trim().toUpperCase();
              if (txt === pick || txt === normalized) {
                btn.click();
              }
            });
          }
          return 1;
        }, batchCols);

        if (batchMarked === 0) {
          break;
        }
        playedCount += batchMarked;

        if (!dryRun) {
          // Verify Nesine wallet balance before submitting
          const balanceIssue = await page.evaluate((requiredCost: number) => {
            const text = (document.body?.innerText ?? "").toLowerCase();
            if (
              text.includes("bakiye yetersiz") ||
              text.includes("yetersiz bakiye") ||
              text.includes("bakiyeniz yetersiz") ||
              text.includes("yeterli bakiye bulunm")
            ) {
              return true;
            }
            const balanceEl = document.querySelector<HTMLElement>(
              "[class*='balance'], [class*='bakiye'], [data-testid*='balance'], .user-balance, .wallet-amount"
            );
            if (balanceEl) {
              const raw = (balanceEl.innerText ?? "")
                .replace(/[^\d.,]/g, "")
                .replace(/\./g, "")
                .replace(",", ".");
              const parsed = Number.parseFloat(raw);
              if (!Number.isNaN(parsed) && (parsed <= 0 || parsed < requiredCost)) {
                return true;
              }
            }
            return false;
          }, totalCostTL);

          if (balanceIssue) {
            const balanceShot = await screenshotSafe(page);
            return jsonResponse<NesineAutoPlayResponse>({
              success: false,
              status: "INSUFFICIENT_BALANCE",
              sessionId,
              playedColumnsCount: playedCount,
              steps: [...steps, "❌ Nesine hesabında yeterli bakiye bulunamadı."],
              screenshotBase64: balanceShot,
              errorMessage: "Bakiye yetersiz",
            });
          }

          await page.evaluate(() => {
            const playBtn = document.querySelector<HTMLElement>(
              "button[class*='play'], button[class*='oyna'], [data-testid='submit-coupon']"
            );
            playBtn?.click();
          });
          await sleep(1200);
        }
      }

      const screenshotBase64 = await screenshotSafe(page);

      if (playedCount === 0) {
        return jsonResponse<NesineAutoPlayResponse>({
          success: false,
          status: "NO_BULLETIN",
          sessionId,
          playedColumnsCount: 0,
          steps: [...steps, "❌ Kupon alanları işaretlenemedi (bülten açık değil)."],
          screenshotBase64,
          errorMessage: "Yeni bülten yüklenmedi",
        });
      }

      if (dryRun) {
        steps.push(`✅ ${playedCount} kolon işaretlendi (Önizleme Modu — oynama onayı gönderilmedi).`);
      } else {
        steps.push(`🚀 ${playedCount} kolon işaretlendi ve Nesine oynama isteği iletildi.`);
      }

      return jsonResponse<NesineAutoPlayResponse>({
        success: true,
        status: "COMPLETED",
        sessionId,
        playedColumnsCount: playedCount,
        steps,
        screenshotBase64,
      });
    } catch (err) {
      return jsonResponse<NesineAutoPlayResponse>(
        {
          success: false,
          status: "ERROR",
          sessionId,
          playedColumnsCount: 0,
          steps,
          errorMessage: err instanceof Error ? err.message : String(err),
        },
        500
      );
    } finally {
      if (browser) {
        await Promise.race([
          browser.close().catch(() => undefined),
          sleep(2500),
        ]);
      }
    }
  },
};

function isAuthorized(request: Request, expectedToken: string): boolean {
  const auth = (request.headers.get("Authorization") ?? "").trim();
  const xToken = (request.headers.get("X-Worker-Token") ?? "").trim();
  const bearer = auth.toLowerCase().startsWith("bearer ") ? auth.slice(7).trim() : "";
  return bearer === expectedToken || xToken === expectedToken;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  return await Promise.race([
    promise,
    new Promise<T>((_, reject) => setTimeout(() => reject(new Error(message)), ms)),
  ]);
}

async function screenshotSafe(
  page: { screenshot: (opts: { encoding: "base64" }) => Promise<unknown> }
): Promise<string | undefined> {
  try {
    const shot = await Promise.race([
      page.screenshot({ encoding: "base64" }),
      sleep(4000).then(() => undefined),
    ]);
    return typeof shot === "string" ? shot : undefined;
  } catch {
    return undefined;
  }
}

function corsHeaders(): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Worker-Token",
  };
}

function jsonResponse<T>(data: T, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...corsHeaders(),
      "Content-Type": "application/json; charset=utf-8",
    },
  });
}
