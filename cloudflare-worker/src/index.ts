import puppeteer, { type Browser, type BrowserWorker } from "@cloudflare/puppeteer";

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
  sessionVerified?: boolean;
  playConfirmed?: boolean;
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
          version: "2.1.0",
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
    let browser: Browser | undefined;

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

      // Dismiss Nesine cookie consent banner ("Kabul Et") if present so it doesn't block login/play buttons
      const dismissedCookie = await dismissCookieBanner(page);
      if (dismissedCookie) {
        await sleep(450);
      }

      // 3. Login check & safe form fill
      steps.push(`3/5 '${payload.username}' hesabı ile oturum kontrol ediliyor...`);
      const preSession = await inspectSessionState(page);

      if (!preSession.loggedIn) {
        const modalClicked = await page.evaluate(() => {
          const hasVisiblePass = Array.from(document.querySelectorAll<HTMLInputElement>("input[type='password']"))
            .some((el) => {
              const r = el.getBoundingClientRect();
              return r.width > 0 && r.height > 0;
            });
          if (hasVisiblePass) return false;

          const byText = Array.from(document.querySelectorAll<HTMLElement>("button, a, div[role='button'], span[role='button']"))
            .find((el) => {
              const r = el.getBoundingClientRect();
              if (r.width <= 0 || r.height <= 0) return false;
              const t = (el.innerText || "").trim().toLowerCase();
              return t === "giriş yap" || t === "giris yap" || t === "üye girişi";
            });
          if (byText) {
            byText.click();
            return true;
          }

          const selectorBtn = document.querySelector<HTMLElement>(
            "[data-testid='login-button'], button[class*='login' i], a[class*='login' i], button.login-btn, .header-login-btn"
          );
          if (selectorBtn) {
            selectorBtn.click();
            return true;
          }
          return false;
        });

        if (modalClicked) {
          await page
            .waitForFunction(
              () => {
                const hasPass = Array.from(document.querySelectorAll("input[type='password']"))
                  .some((el) => {
                    const r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                  });
                return hasPass || document.querySelectorAll("iframe").length > 0;
              },
              { timeout: 3500 }
            )
            .catch(() => sleep(1000));
        }

        // Fill credentials across main document or any child login iframe
        const fillCredentialsInContext = async (ctx: {
          evaluate: <A extends unknown[], R>(fn: (...args: A) => R, ...args: A) => Promise<R>;
        }) => {
          return await ctx
            .evaluate(
              (user: string, pass: string) => {
                const visiblePass = Array.from(document.querySelectorAll<HTMLInputElement>("input[type='password']"))
                  .find((el) => {
                    const r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                  });
                if (!visiblePass) return false;

                const userSelectors = [
                  "input[name='username']", "input[name='Username']", "input[name='UserName']",
                  "input[name*='member' i]", "input[name*='user' i]",
                  "input[id*='user' i]", "input[id*='member' i]", "input[id*='tc' i]",
                  "input[placeholder*='Üye' i]", "input[placeholder*='T.C.' i]",
                  "input[placeholder*='Kimlik' i]", "input[placeholder*='TC' i]",
                  "input[autocomplete='username']", "input[type='tel']"
                ];
                let visibleUser: HTMLInputElement | null = null;
                for (const sel of userSelectors) {
                  const found = Array.from(document.querySelectorAll<HTMLInputElement>(sel))
                    .find((el) => {
                      const r = el.getBoundingClientRect();
                      return r.width > 0 && r.height > 0 && el.type !== "password";
                    });
                  if (found) {
                    visibleUser = found;
                    break;
                  }
                }
                if (!visibleUser) {
                  const scopeEl = visiblePass.closest("form, .modal, [role='dialog'], div") ?? document;
                  visibleUser = Array.from(
                    scopeEl.querySelectorAll<HTMLInputElement>("input[type='text'], input[type='tel'], input[type='number'], input:not([type])")
                  ).find((el) => {
                    const r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0 && el !== visiblePass;
                  }) ?? null;
                }
                if (!visibleUser) return false;

                const setNativeValue = (el: HTMLInputElement, val: string) => {
                  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
                  if (setter) setter.call(el, val);
                  else el.value = val;
                  el.dispatchEvent(new Event("input", { bubbles: true }));
                  el.dispatchEvent(new Event("change", { bubbles: true }));
                  el.dispatchEvent(new Event("blur", { bubbles: true }));
                };

                visibleUser.focus();
                setNativeValue(visibleUser, user);
                visiblePass.focus();
                setNativeValue(visiblePass, pass);

                const form = visiblePass.closest("form, .modal, [role='dialog']") ?? document;
                let submit = Array.from(form.querySelectorAll<HTMLElement>("button, a[role='button'], input[type='submit']"))
                  .find((el) => {
                    const r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) return false;
                    const t = (el.innerText || (el as HTMLInputElement).value || "").trim().toLowerCase();
                    return t === "giriş yap" || t === "giris yap" || t === "giriş" || t === "devam et";
                  }) ?? null;
                if (!submit) {
                  submit = form.querySelector<HTMLElement>("button[type='submit'], input[type='submit'], button[class*='login' i]");
                }
                submit?.click();
                return true;
              },
              payload.username.trim(),
              payload.password
            )
            .catch(() => false);
        };

        let loginSubmitted = await fillCredentialsInContext(page);
        if (!loginSubmitted) {
          for (const frame of page.frames()) {
            if (frame === page.mainFrame()) continue;
            if (await fillCredentialsInContext(frame)) {
              loginSubmitted = true;
              break;
            }
          }
        }

        if (loginSubmitted) {
          await page
            .waitForFunction(
              () => {
                const txt = (document.body?.innerText ?? "").toLowerCase();
                const hasBalance = !!document.querySelector("[class*='balance' i], [class*='bakiye' i], .user-balance, .wallet-amount, a[href*='hesabim']");
                return (
                  hasBalance ||
                  txt.includes("hesabım") ||
                  txt.includes("çıkış") ||
                  txt.includes("sms şifresi") ||
                  txt.includes("doğrulama kodu") ||
                  txt.includes("hatalı şifre") ||
                  txt.includes("doğrulama")
                );
              },
              { timeout: 5000 }
            )
            .catch(() => sleep(1500));

          if (!page.url().includes("/sportoto")) {
            await page.goto(NESINE_SPORTOTO_URL, { waitUntil: "domcontentloaded", timeout: 15000 }).catch(() => undefined);
            await dismissCookieBanner(page);
          }
        }
      }

      const postSession = await inspectSessionState(page);
      if (postSession.requires2fa) {
        const shot = await screenshotSafe(page);
        return jsonResponse<NesineAutoPlayResponse>({
          success: false,
          status: "REQUIRES_2FA",
          sessionId,
          playedColumnsCount: 0,
          sessionVerified: false,
          playConfirmed: false,
          steps: [...steps, "📲 Nesine SMS doğrulama (2FA) / ek onay kodu istedi."],
          screenshotBase64: shot,
          errorMessage: "Nesine SMS doğrulaması istedi. Uygulama içi WebView robotu üzerinden SMS kodunu girerek oynayabilirsiniz.",
        });
      }

      if (postSession.hasLoginError) {
        const shot = await screenshotSafe(page);
        return jsonResponse<NesineAutoPlayResponse>({
          success: false,
          status: "LOGIN_FAILED",
          sessionId,
          playedColumnsCount: 0,
          sessionVerified: false,
          playConfirmed: false,
          steps: [...steps, "❌ Nesine kullanıcı adı veya şifre hatalı."],
          screenshotBase64: shot,
          errorMessage: "Nesine girişi reddedildi: kullanıcı adı veya şifre hatalı.",
        });
      }

      if (!dryRun && !postSession.loggedIn) {
        const shot = await screenshotSafe(page);
        return jsonResponse<NesineAutoPlayResponse>({
          success: false,
          status: "LOGIN_FAILED",
          sessionId,
          playedColumnsCount: 0,
          sessionVerified: false,
          playConfirmed: false,
          steps: [...steps, "❌ Nesine oturumu açılamadı (sayfada hâlâ 'Giriş Yap' görünüyor)."],
          screenshotBase64: shot,
          errorMessage: "Nesine oturumu açılmadı (bulut tarayıcıda giriş tamamlanamadı). Kuponu oynamak için Uygulama İçi Tarayıcı Robotu (WebView) üzerinden giriş yapın.",
        });
      }
      if (dryRun && !postSession.loggedIn) {
        steps.push("⚠️ Oturum kapalı (Önizleme Modu nedeniyle yalnızca kolon işaretlemesi devam ediyor).");
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

      let anyPlayConfirmed = false;
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
          await dismissCookieBanner(page);

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
              sessionVerified: postSession.loggedIn,
              playConfirmed: anyPlayConfirmed,
              steps: [...steps, "❌ Nesine hesabında yeterli bakiye bulunamadı."],
              screenshotBase64: balanceShot,
              errorMessage: "Bakiye yetersiz",
            });
          }

          const playClicked = await page.evaluate(() => {
            const selectorBtn = document.querySelector<HTMLElement>(
              "[data-testid='submit-coupon'], button[class*='hemen-oyna' i], button[class*='play' i], button[class*='oyna' i]"
            );
            const targetBtn =
              selectorBtn ??
              Array.from(document.querySelectorAll<HTMLElement>("button, a[role='button'], input[type='submit']"))
                .find((el) => {
                  const r = el.getBoundingClientRect();
                  if (r.width <= 0 || r.height <= 0) return false;
                  const t = (el.innerText || "").trim().toLowerCase();
                  return t === "hemen oyna" || t === "kuponu oyna" || t === "oyna" || t === "kupon yatır";
                }) ??
              null;
            if (!targetBtn) return false;
            targetBtn.scrollIntoView({ behavior: "smooth", block: "center" });
            targetBtn.click();
            return true;
          });

          if (!playClicked) {
            const noPlayShot = await screenshotSafe(page);
            return jsonResponse<NesineAutoPlayResponse>({
              success: false,
              status: "ERROR",
              sessionId,
              playedColumnsCount: playedCount,
              sessionVerified: postSession.loggedIn,
              playConfirmed: anyPlayConfirmed,
              steps: [...steps, "❌ 'Hemen Oyna' butonu bulunamadı; oynama onayı açılamadı."],
              screenshotBase64: noPlayShot,
              errorMessage: "Nesine sayfasında 'Hemen Oyna' butonu bulunamadı. Uygulama İçi Tarayıcı Robotu (WebView) ile devam edin.",
            });
          }

          await sleep(1400);

          // Check if clicking play prompted login/2FA/insufficient balance OR opened confirmation modal
          const postPlayOutcome = await page.evaluate(() => {
            const bodyTxt = (document.body?.innerText ?? "").toLowerCase();
            const hasPassword = Array.from(document.querySelectorAll<HTMLInputElement>("input[type='password']"))
              .some((el) => {
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              });
            if (hasPassword || bodyTxt.includes("üye girişi yapmanız")) {
              return { state: "NOT_LOGGED_IN" };
            }
            if (bodyTxt.includes("bakiye yetersiz") || bodyTxt.includes("yetersiz bakiye")) {
              return { state: "INSUFFICIENT_BALANCE" };
            }
            if (bodyTxt.includes("sms şifresi") || bodyTxt.includes("doğrulama kodu")) {
              return { state: "REQUIRES_2FA" };
            }
            const confirmBtn = Array.from(
              document.querySelectorAll<HTMLElement>(".modal button, [role='dialog'] button, [class*='modal' i] button, [class*='dialog' i] button, button")
            ).find((el) => {
              const r = el.getBoundingClientRect();
              if (r.width <= 0 || r.height <= 0) return false;
              const t = (el.innerText || "").trim().toLowerCase();
              return t === "onayla" || t === "kuponu onayla" || t === "oynamayı onayla" || t === "evet";
            });
            if (confirmBtn) {
              confirmBtn.click();
              return { state: "CONFIRMED", label: confirmBtn.innerText.trim() };
            }
            return { state: "NO_MODAL" };
          });

          if (postPlayOutcome.state === "NOT_LOGGED_IN") {
            const shot = await screenshotSafe(page);
            return jsonResponse<NesineAutoPlayResponse>({
              success: false,
              status: "LOGIN_FAILED",
              sessionId,
              playedColumnsCount: playedCount,
              sessionVerified: false,
              playConfirmed: false,
              steps: [...steps, "❌ Oynama aşamasında Nesine oturum açılmasını istedi."],
              screenshotBase64: shot,
              errorMessage: "Oynama onayı için Nesine oturumu kapalı görünüyor. WebView robotundan giriş yapıp oynayabilirsiniz.",
            });
          }
          if (postPlayOutcome.state === "INSUFFICIENT_BALANCE") {
            const shot = await screenshotSafe(page);
            return jsonResponse<NesineAutoPlayResponse>({
              success: false,
              status: "INSUFFICIENT_BALANCE",
              sessionId,
              playedColumnsCount: playedCount,
              sessionVerified: postSession.loggedIn,
              playConfirmed: false,
              steps: [...steps, "❌ Oynama onayında yetersiz bakiye uyarısı alındı."],
              screenshotBase64: shot,
              errorMessage: "Bakiye yetersiz",
            });
          }
          if (postPlayOutcome.state === "REQUIRES_2FA") {
            const shot = await screenshotSafe(page);
            return jsonResponse<NesineAutoPlayResponse>({
              success: false,
              status: "REQUIRES_2FA",
              sessionId,
              playedColumnsCount: playedCount,
              sessionVerified: postSession.loggedIn,
              playConfirmed: false,
              steps: [...steps, "📲 Oynama onayı için SMS (2FA) doğrulaması gerekli."],
              screenshotBase64: shot,
              errorMessage: "Oynama onayı için SMS doğrulaması gerekiyor; WebView robotuyla devam edebilirsiniz.",
            });
          }
          if (postPlayOutcome.state === "CONFIRMED") {
            anyPlayConfirmed = true;
            await sleep(1400);
            steps.push(`✅ Nesine oynama onayı verildi (${postPlayOutcome.label ?? "Onayla"}).`);
          }
        }
      }

      const screenshotBase64 = await screenshotSafe(page);

      if (playedCount === 0) {
        return jsonResponse<NesineAutoPlayResponse>({
          success: false,
          status: "NO_BULLETIN",
          sessionId,
          playedColumnsCount: 0,
          sessionVerified: postSession.loggedIn,
          playConfirmed: false,
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
        sessionVerified: postSession.loggedIn,
        playConfirmed: dryRun ? false : anyPlayConfirmed,
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

async function dismissCookieBanner(page: {
  evaluate: <R>(fn: () => R) => Promise<R>;
}): Promise<boolean> {
  return await page
    .evaluate(() => {
      const acceptById = document.querySelector<HTMLElement>(
        "#onetrust-accept-btn-handler, button[data-testid*='cookie-accept' i], button[class*='cookie-accept' i]"
      );
      if (acceptById) {
        acceptById.click();
        return true;
      }
      const btn = Array.from(document.querySelectorAll<HTMLElement>("button, a"))
        .find((el) => {
          const r = el.getBoundingClientRect();
          if (r.width <= 0 || r.height <= 0) return false;
          const t = (el.innerText || "").trim().toLowerCase();
          return t === "kabul et" || t === "tümünü kabul et" || t === "çerezleri kabul et";
        });
      if (btn) {
        btn.click();
        return true;
      }
      return false;
    })
    .catch(() => false);
}

async function inspectSessionState(page: {
  evaluate: <R>(fn: () => R) => Promise<R>;
}): Promise<{ loggedIn: boolean; requires2fa: boolean; hasLoginError: boolean }> {
  return await page
    .evaluate(() => {
      const bodyText = (document.body?.innerText ?? "").toLowerCase();
      const headerEl = document.querySelector("header, nav, .header, [class*='header' i]");
      const headerText = (headerEl instanceof HTMLElement ? headerEl.innerText : bodyText.slice(0, 1500)).toLowerCase();
      const hasBalanceEl = !!document.querySelector(
        "[class*='balance' i], [class*='bakiye' i], [data-testid*='balance' i], .user-balance, .wallet-amount, a[href*='hesabim'], a[href*='para-yatir']"
      );
      const hasLoggedInText =
        headerText.includes("hesabım") ||
        headerText.includes("hesabim") ||
        headerText.includes("çıkış") ||
        headerText.includes("cikis") ||
        headerText.includes("para yatır") ||
        headerText.includes("kuponlarım");
      const visibleLoginBySelector = Array.from(
        document.querySelectorAll<HTMLElement>("[data-testid='login-button'], .login-btn, .header-login-btn, button[class*='login' i], a[class*='login' i]")
      ).some((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      });
      const visibleLoginByText = Array.from(document.querySelectorAll<HTMLElement>("button, a"))
        .some((el) => {
          const r = el.getBoundingClientRect();
          if (r.width <= 0 || r.height <= 0) return false;
          const t = (el.innerText || "").trim().toLowerCase();
          return t === "giriş yap" || t === "giris yap" || t === "üye ol";
        });
      const visiblePassword = Array.from(document.querySelectorAll<HTMLInputElement>("input[type='password']"))
        .some((el) => {
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0;
        });

      return {
        loggedIn: (hasBalanceEl || hasLoggedInText) && !visiblePassword && !visibleLoginBySelector && !visibleLoginByText,
        requires2fa:
          bodyText.includes("sms şifresi") ||
          bodyText.includes("doğrulama kodu") ||
          bodyText.includes("tek kullanımlık") ||
          bodyText.includes("otp"),
        hasLoginError:
          bodyText.includes("hatalı şifre") ||
          bodyText.includes("şifreniz hatalı") ||
          bodyText.includes("üye numaranız veya şifreniz") ||
          bodyText.includes("kullanıcı adı veya şifre"),
      };
    })
    .catch(() => ({ loggedIn: false, requires2fa: false, hasLoginError: false }));
}

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
