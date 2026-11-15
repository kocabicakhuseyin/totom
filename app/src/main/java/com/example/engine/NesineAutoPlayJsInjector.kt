package com.example.engine

import com.example.model.TotoColumn

/**
 * Generates DOM JavaScript scripts injected into Android WebView
 * when automating login, session verification, column marking, and play confirmation
 * on nesine.com/sportoto.
 */
object NesineAutoPlayJsInjector {

    private fun escapeJsString(value: String): String {
        val sb = StringBuilder("\"")
        value.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * Dismisses Nesine's GDPR/KVKK cookie consent banner ("Kabul Et") if visible so it
     * does not obscure login or play buttons at the bottom of the screen.
     */
    fun buildCookieBannerDismissScript(): String {
        return """
            (function() {
                try {
                    var byId = document.querySelector("#onetrust-accept-btn-handler, button[data-testid*='cookie-accept' i], button[class*='cookie-accept' i]");
                    if (byId) {
                        var r = byId.getBoundingClientRect();
                        if (r.width > 0 && r.height > 0) {
                            byId.click();
                            return JSON.stringify({ dismissed: true });
                        }
                    }
                    var btns = document.querySelectorAll("button, a");
                    for (var i = 0; i < btns.length; i++) {
                        var br = btns[i].getBoundingClientRect();
                        if (br.width <= 0 || br.height <= 0) continue;
                        var t = (btns[i].innerText || "").trim().toLowerCase();
                        if (t === "kabul et" || t === "tümünü kabul et" || t === "çerezleri kabul et") {
                            btns[i].click();
                            return JSON.stringify({ dismissed: true });
                        }
                    }
                    return JSON.stringify({ dismissed: false });
                } catch (e) {
                    return JSON.stringify({ dismissed: false });
                }
            })();
        """.trimIndent()
    }

    /**
     * Checks whether the current Nesine WebView page has an active authenticated member session
     * and whether a 15-match Spor Toto bulletin is currently loaded.
     */
    fun buildSessionCheckScript(): String {
        return """
            (function() {
                try {
                    var bodyText = (document.body && document.body.innerText ? document.body.innerText : "").toLowerCase();
                    var headerEl = document.querySelector("header, nav, .header, [class*='header' i], [class*='navbar' i]");
                    var headerText = (headerEl && headerEl.innerText ? headerEl.innerText : bodyText.slice(0, 1500)).toLowerCase();

                    var hasBalanceEl = !!document.querySelector(
                        "[class*='balance' i], [class*='bakiye' i], [data-testid*='balance' i], .user-balance, .wallet-amount, a[href*='hesabim'], a[href*='para-yatir'], button[class*='account' i]"
                    );
                    var hasLoggedInText =
                        headerText.indexOf("hesabım") !== -1 ||
                        headerText.indexOf("hesabim") !== -1 ||
                        headerText.indexOf("çıkış") !== -1 ||
                        headerText.indexOf("cikis") !== -1 ||
                        headerText.indexOf("para yatır") !== -1 ||
                        headerText.indexOf("para yatir") !== -1 ||
                        headerText.indexOf("kuponlarım") !== -1;

                    var loginButtons = document.querySelectorAll(
                        "[data-testid='login-button'], .login-btn, .header-login-btn, a[href*='uyelik'], a[href*='login'], button[class*='login' i], a[class*='login' i]"
                    );
                    var visibleLoginTrigger = false;
                    for (var i = 0; i < loginButtons.length; i++) {
                        var rect = loginButtons[i].getBoundingClientRect();
                        if (rect.width > 0 && rect.height > 0) {
                            visibleLoginTrigger = true;
                            break;
                        }
                    }
                    if (!visibleLoginTrigger) {
                        var allActionEls = document.querySelectorAll("button, a");
                        for (var a = 0; a < allActionEls.length; a++) {
                            var ar = allActionEls[a].getBoundingClientRect();
                            if (ar.width <= 0 || ar.height <= 0) continue;
                            var at = (allActionEls[a].innerText || "").trim().toLowerCase();
                            if (at === "giriş yap" || at === "giris yap" || at === "üye ol") {
                                visibleLoginTrigger = true;
                                break;
                            }
                        }
                    }

                    var hasOpenPasswordForm = false;
                    var passEls = document.querySelectorAll("input[type='password']");
                    for (var p = 0; p < passEls.length; p++) {
                        var pr = passEls[p].getBoundingClientRect();
                        if (pr.width > 0 && pr.height > 0) {
                            hasOpenPasswordForm = true;
                            break;
                        }
                    }

                    var requires2fa =
                        bodyText.indexOf("sms şifresi") !== -1 ||
                        bodyText.indexOf("sms doğrulama") !== -1 ||
                        bodyText.indexOf("doğrulama kodu") !== -1 ||
                        bodyText.indexOf("tek kullanımlık") !== -1;

                    var loggedIn = (hasBalanceEl || hasLoggedInText) && !hasOpenPasswordForm && !visibleLoginTrigger;
                    if (!loggedIn && !visibleLoginTrigger && !hasOpenPasswordForm && hasLoggedInText) {
                        loggedIn = true;
                    }

                    var hasBulletin = !!document.getElementById("m-c-0-0-0") ||
                        document.querySelectorAll("input[id^='m-c-']").length >= 15 ||
                        document.querySelectorAll(".sportoto-row, [data-match-row], tr[data-match-no], .toto-match-item").length >= 15;

                    var hasConfirmButton = false;
                    var confirmCandidates = document.querySelectorAll("button, a[role='button'], input[type='button'], input[type='submit']");
                    for (var cb = 0; cb < confirmCandidates.length; cb++) {
                        var cbr = confirmCandidates[cb].getBoundingClientRect();
                        if (cbr.width <= 0 || cbr.height <= 0) continue;
                        var cbt = (confirmCandidates[cb].innerText || confirmCandidates[cb].value || "").trim().toLowerCase();
                        if (cbt === "onayla" || cbt === "kuponu onayla" || cbt === "oynamayı onayla") {
                            hasConfirmButton = true;
                            break;
                        }
                    }
                    var hasConfirmView = hasConfirmButton || (bodyText.indexOf("kupon tutarı") !== -1 && bodyText.indexOf("misli") !== -1);
                    var hasReceiptView =
                        bodyText.indexOf("kuponunuz oynan") !== -1 ||
                        bodyText.indexOf("oynama işleminiz gerçekleş") !== -1 ||
                        bodyText.indexOf("kuponunuz onaylan") !== -1;
                    var insufficientBalance =
                        bodyText.indexOf("bakiye yetersiz") !== -1 ||
                        bodyText.indexOf("yetersiz bakiye") !== -1 ||
                        bodyText.indexOf("bakiyeniz yetersiz") !== -1 ||
                        bodyText.indexOf("yeterli bakiye bulunm") !== -1;

                    return JSON.stringify({
                        loggedIn: loggedIn,
                        requires2fa: requires2fa,
                        hasOpenLoginForm: hasOpenPasswordForm,
                        hasBulletin: hasBulletin,
                        hasConfirmView: hasConfirmView,
                        hasReceiptView: hasReceiptView,
                        insufficientBalance: insufficientBalance
                    });
                } catch (e) {
                    return JSON.stringify({
                        loggedIn: false,
                        requires2fa: false,
                        hasOpenLoginForm: false,
                        hasBulletin: false,
                        hasConfirmView: false,
                        hasReceiptView: false,
                        insufficientBalance: false,
                        error: e.message
                    });
                }
            })();
        """.trimIndent()
    }

    /**
     * If Nesine is currently on the mobile Coupon Review/Onayla summary page or a post-play receipt page,
     * clicks "Yeni Kupon" / "Tekrar Oyna" / back chevron "<" to return to the 15-match Spor Toto grid.
     */
    fun buildReturnToBulletinScript(): String {
        return """
            (function() {
                try {
                    var hasBulletin = !!document.getElementById("m-c-0-0-0") ||
                        document.querySelectorAll("input[id^='m-c-']").length >= 15 ||
                        document.querySelectorAll(".sportoto-row, [data-match-row], tr[data-match-no], .toto-match-item").length >= 15;
                    if (hasBulletin) {
                        return JSON.stringify({ ready: true, clicked: false, action: "ALREADY_READY" });
                    }

                    var allBtns = document.querySelectorAll("button, a[role='button'], a, input[type='button'], input[type='submit']");
                    var onaylaBtn = null;
                    for (var i = 0; i < allBtns.length; i++) {
                        var r = allBtns[i].getBoundingClientRect();
                        if (r.width <= 0 || r.height <= 0) continue;
                        var t = (allBtns[i].innerText || allBtns[i].value || "").trim().toLowerCase();
                        if (t === "yeni kupon" || t === "yeni kupon yap" || t === "yeni kupon oluştur" ||
                            t === "tekrar oyna" || t === "bültene dön" || t === "spor totoya dön" || t === "spor toto oyna") {
                            allBtns[i].click();
                            return JSON.stringify({ ready: false, clicked: true, action: "CLICKED_NEW_COUPON" });
                        }
                        if (t === "onayla" || t === "kuponu onayla" || t === "oynamayı onayla") {
                            onaylaBtn = allBtns[i];
                        }
                    }

                    if (onaylaBtn) {
                        var container = onaylaBtn.parentElement ? (onaylaBtn.parentElement.parentElement || onaylaBtn.parentElement) : document;
                        var nearBtns = container.querySelectorAll("button, a[role='button'], a");
                        for (var j = 0; j < nearBtns.length; j++) {
                            if (nearBtns[j] === onaylaBtn) continue;
                            var nr = nearBtns[j].getBoundingClientRect();
                            if (nr.width <= 0 || nr.height <= 0) continue;
                            var nt = (nearBtns[j].innerText || "").trim().toLowerCase();
                            var aria = (nearBtns[j].getAttribute("aria-label") || "").toLowerCase();
                            if (nt === "<" || nt === "‹" || nt === "geri" || aria.indexOf("geri") !== -1 || aria.indexOf("back") !== -1 || (nr.width <= 72 && nr.left < onaylaBtn.getBoundingClientRect().left)) {
                                nearBtns[j].click();
                                return JSON.stringify({ ready: false, clicked: true, action: "CLICKED_BACK_FROM_CONFIRM" });
                            }
                        }
                    }

                    var modalClose = document.querySelectorAll(".modal button, [role='dialog'] button, [class*='modal' i] button");
                    for (var m = 0; m < modalClose.length; m++) {
                        var mr = modalClose[m].getBoundingClientRect();
                        if (mr.width <= 0 || mr.height <= 0) continue;
                        var mt = (modalClose[m].innerText || "").trim().toLowerCase();
                        if (mt === "tamam" || mt === "kapat" || mt === "yeni kupon") {
                            modalClose[m].click();
                            return JSON.stringify({ ready: false, clicked: true, action: "DISMISSED_MODAL" });
                        }
                    }

                    return JSON.stringify({ ready: false, clicked: false, action: "WAITING" });
                } catch (e) {
                    return JSON.stringify({ ready: false, clicked: false, action: "ERROR", error: e.message });
                }
            })();
        """.trimIndent()
    }

    fun buildLoginFillScript(
        username: String,
        password: String,
        autoSubmit: Boolean = true
    ): String {
        val safeUser = escapeJsString(username)
        val safePass = escapeJsString(password)
        return """
            (function() {
                try {
                    var bodyText = (document.body && document.body.innerText ? document.body.innerText : "").toLowerCase();
                    var findPassInput = function() {
                        var list = document.querySelectorAll("input[type='password']");
                        for (var i = 0; i < list.length; i++) {
                            var r = list[i].getBoundingClientRect();
                            if (r.width > 0 && r.height > 0) return list[i];
                        }
                        return list[0] || null;
                    };
                    var findUserInput = function(passNode) {
                        var selectors = [
                            "input[name='username']", "input[name='Username']", "input[name='UserName']",
                            "input[name*='member' i]", "input[name*='user' i]",
                            "input[id*='user' i]", "input[id*='member' i]", "input[id*='tc' i]",
                            "input[placeholder*='Üye' i]", "input[placeholder*='T.C.' i]",
                            "input[placeholder*='Kimlik' i]", "input[placeholder*='TC' i]",
                            "input[autocomplete='username']", "input[type='tel']"
                        ];
                        for (var s = 0; s < selectors.length; s++) {
                            var els = document.querySelectorAll(selectors[s]);
                            for (var j = 0; j < els.length; j++) {
                                var r = els[j].getBoundingClientRect();
                                if (r.width > 0 && r.height > 0 && els[j].type !== "password") return els[j];
                            }
                        }
                        if (passNode) {
                            var scopeEl = passNode.closest("form, .modal, [role='dialog'], div") || document;
                            var fallbackInputs = scopeEl.querySelectorAll("input[type='text'], input[type='tel'], input[type='number'], input:not([type])");
                            for (var fi = 0; fi < fallbackInputs.length; fi++) {
                                var fr = fallbackInputs[fi].getBoundingClientRect();
                                if (fr.width > 0 && fr.height > 0 && fallbackInputs[fi] !== passNode) {
                                    return fallbackInputs[fi];
                                }
                            }
                        }
                        return null;
                    };

                    var passEl = findPassInput();
                    var userEl = findUserInput(passEl);

                    if (!userEl || !passEl) {
                        var trigger = null;
                        var allBtns = document.querySelectorAll("button, a, div[role='button'], span[role='button']");
                        for (var k = 0; k < allBtns.length; k++) {
                            var br = allBtns[k].getBoundingClientRect();
                            if (br.width <= 0 || br.height <= 0) continue;
                            var t = (allBtns[k].innerText || "").trim().toLowerCase();
                            if (t === "giriş yap" || t === "giris yap" || t === "üye girişi") {
                                trigger = allBtns[k];
                                break;
                            }
                        }
                        if (!trigger) {
                            trigger = document.querySelector("[data-testid='login-button'], .login-btn, .header-login-btn, a[href*='uyelik'], a[href*='login'], button[class*='login' i], a[class*='login' i]");
                        }
                        if (trigger) {
                            trigger.click();
                            return JSON.stringify({
                                filled: false,
                                openedModal: true,
                                reason: "Nesine giriş ekranı açıldı. Form yüklenince bilgileriniz otomatik girilecek."
                            });
                        }
                        var hasAccountText = bodyText.indexOf("hesabım") !== -1 || bodyText.indexOf("çıkış") !== -1 || !!document.querySelector("[class*='balance' i], [class*='bakiye' i]");
                        if (hasAccountText) {
                            return JSON.stringify({ filled: true, alreadyLoggedIn: true, reason: "Nesine oturumu zaten açık görünüyor." });
                        }
                        return JSON.stringify({ filled: false, reason: "Giriş formu bulunamadı. Sayfadaki Giriş Yap butonuna dokunun." });
                    }

                    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                    userEl.focus();
                    nativeSetter.call(userEl, $safeUser);
                    userEl.dispatchEvent(new Event("input", { bubbles: true }));
                    userEl.dispatchEvent(new Event("change", { bubbles: true }));

                    passEl.focus();
                    nativeSetter.call(passEl, $safePass);
                    passEl.dispatchEvent(new Event("input", { bubbles: true }));
                    passEl.dispatchEvent(new Event("change", { bubbles: true }));

                    var submitted = false;
                    if ($autoSubmit && ($safeUser).length > 0 && ($safePass).length > 0) {
                        var form = passEl.closest("form") || document;
                        var submitBtn = form.querySelector("button[type='submit'], input[type='submit'], button[class*='submit' i], button[class*='login' i]");
                        if (!submitBtn) {
                            var candidates = form.querySelectorAll("button, a[role='button']");
                            for (var b = 0; b < candidates.length; b++) {
                                var txt = (candidates[b].innerText || "").trim().toLowerCase();
                                if (txt === "giriş yap" || txt === "giris yap" || txt === "giriş" || txt === "devam et") {
                                    submitBtn = candidates[b];
                                    break;
                                }
                            }
                        }
                        if (submitBtn) {
                            submitBtn.click();
                            submitted = true;
                        }
                    }

                    return JSON.stringify({
                        filled: true,
                        submitted: submitted,
                        reason: submitted ? "Giriş bilgileri gönderildi (SMS 2FA istenirse kodu ekrana girin)." : "Kullanıcı adı ve şifre dolduruldu."
                    });
                } catch (e) {
                    return JSON.stringify({ filled: false, reason: e.message });
                }
            })();
        """.trimIndent()
    }

    fun buildColumnFillScript(column: TotoColumn, slotIndex: Int = 0): String {
        val safeSlot = slotIndex.coerceIn(0, 3)
        val picksArray = column.predictions.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { "\"${it.code}\"" }

        return """
            (function() {
                try {
                    var picks = $picksArray;
                    var pageText = (document.body && document.body.innerText ? document.body.innerText : "").toLowerCase();
                    if (pageText.indexOf("bakiye yetersiz") !== -1 ||
                        pageText.indexOf("yetersiz bakiye") !== -1 ||
                        pageText.indexOf("bakiyeniz yetersiz") !== -1 ||
                        pageText.indexOf("yeterli bakiye bulunm") !== -1) {
                        return JSON.stringify({
                            success: false,
                            clickedCount: 0,
                            message: "Bakiye yetersiz"
                        });
                    }
                    if (pageText.indexOf("aktif spor toto programı bulunmamaktadır") !== -1 ||
                        pageText.indexOf("aktif spor toto programi bulunmamaktadir") !== -1 ||
                        pageText.indexOf("aktif bülten yok") !== -1) {
                        return JSON.stringify({
                            success: false,
                            clickedCount: 0,
                            message: "Yeni bülten yüklenmedi"
                        });
                    }
                    var hasNativeGrid = !!document.getElementById("m-c-0-0-0");
                    if (hasNativeGrid) {
                        var marked = 0;
                        for (var m = 0; m < 15 && m < picks.length; m++) {
                            var p = (picks[m] || "").trim().toUpperCase();
                            var outcomeIdx = p === "1" ? 0 : (p === "X" || p === "0") ? 1 : 2;
                            for (var o = 0; o < 3; o++) {
                                var cell = document.getElementById("m-c-" + m + "-$safeSlot-" + o);
                                if (cell) {
                                    var want = (o === outcomeIdx);
                                    if (cell.checked !== want) { cell.click(); }
                                    if (want) { marked++; }
                                }
                            }
                        }
                        return JSON.stringify({
                            success: marked > 0,
                            clickedCount: marked,
                            message: "Kolon #${column.index} (Sütun ${safeSlot + 1}) için " + marked + "/15 maç işaretlendi."
                        });
                    }

                    var rowSelectors = [
                        ".sportoto-row",
                        "[data-match-row]",
                        "tr[data-match-no]",
                        ".toto-match-item",
                        "[class*='sportoto'] [class*='row']",
                        "table tbody tr"
                    ];
                    var rows = [];
                    for (var s = 0; s < rowSelectors.length; s++) {
                        var candidate = document.querySelectorAll(rowSelectors[s]);
                        if (candidate && candidate.length >= 15) {
                            rows = candidate;
                            break;
                        }
                    }
                    if (!rows || rows.length < 15) {
                        return JSON.stringify({
                            success: false,
                            clickedCount: 0,
                            message: "Yeni bülten yüklenmedi (Oynanacak 15 maçlık bülten bulunamadı, satır: " + (rows ? rows.length : 0) + ")."
                        });
                    }
                    var clicked = 0;
                    for (var i = 0; i < 15 && i < picks.length; i++) {
                        var pick = picks[i];
                        var altPick = pick === "0" ? "X" : (pick === "X" ? "0" : pick);
                        var row = rows[i];
                        var btns = row.querySelectorAll("button, a, label, td, span, div[role='button']");
                        for (var j = 0; j < btns.length; j++) {
                            var txt = (btns[j].innerText || "").trim().toUpperCase();
                            if (txt === pick || txt === altPick) {
                                btns[j].click();
                                clicked++;
                                break;
                            }
                        }
                    }
                    return JSON.stringify({
                        success: clicked > 0,
                        clickedCount: clicked,
                        message: "Kolon #${column.index} için " + clicked + "/15 maç işaretlendi."
                    });
                } catch (e) {
                    return JSON.stringify({ success: false, clickedCount: 0, message: e.message });
                }
            })();
        """.trimIndent()
    }

    /**
     * Clears any currently checked selections across all 4 Nesine Spor Toto slip columns
     * (#m-c-{match}-{slot}-{outcome}) before filling the next 4-column batch.
     */
    fun buildClearGridScript(): String {
        return """
            (function() {
                try {
                    var cleared = 0;
                    for (var slot = 0; slot < 4; slot++) {
                        for (var m = 0; m < 15; m++) {
                            for (var o = 0; o < 3; o++) {
                                var cell = document.getElementById("m-c-" + m + "-" + slot + "-" + o);
                                if (cell && cell.checked) {
                                    cell.click();
                                    cleared++;
                                }
                            }
                        }
                    }
                    return JSON.stringify({ cleared: cleared });
                } catch (e) {
                    return JSON.stringify({ cleared: 0, error: e.message });
                }
            })();
        """.trimIndent()
    }

    /**
     * Fills a column either into its extended DOM slot (if Nesine renders >4 columns natively,
     * e.g. m-c-0-4-0..m-c-0-47-0) or falls back to the 4-column A-D slip slot (rawIndex % 4).
     */
    fun buildColumnFillAutoSlotScript(column: TotoColumn, rawIndex: Int): String {
        val safeRaw = rawIndex.coerceAtLeast(0)
        val fallbackSlot = safeRaw % 4
        val picksArray = column.predictions.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { "\"${it.code}\"" }

        return """
            (function() {
                try {
                    var hasExtended = !!document.getElementById("m-c-0-$safeRaw-0");
                    var targetSlot = hasExtended ? $safeRaw : $fallbackSlot;
                    var picks = $picksArray;
                    var hasNativeGrid = !!document.getElementById("m-c-0-0-0");
                    if (hasNativeGrid) {
                        var marked = 0;
                        for (var m = 0; m < 15 && m < picks.length; m++) {
                            var p = (picks[m] || "").trim().toUpperCase();
                            var outcomeIdx = p === "1" ? 0 : (p === "X" || p === "0") ? 1 : 2;
                            for (var o = 0; o < 3; o++) {
                                var cell = document.getElementById("m-c-" + m + "-" + targetSlot + "-" + o);
                                if (cell) {
                                    var want = (o === outcomeIdx);
                                    if (cell.checked !== want) { cell.click(); }
                                    if (want) { marked++; }
                                }
                            }
                        }
                        return JSON.stringify({
                            success: marked > 0,
                            usedExtendedSlot: hasExtended,
                            slotUsed: targetSlot,
                            clickedCount: marked,
                            message: "Kolon #${column.index} (Sütun " + (targetSlot + 1) + ") için " + marked + "/15 maç işaretlendi."
                        });
                    }
                    return JSON.stringify({
                        success: false,
                        usedExtendedSlot: false,
                        slotUsed: targetSlot,
                        clickedCount: 0,
                        message: "Yeni bülten yüklenmedi"
                    });
                } catch (e) {
                    return JSON.stringify({ success: false, usedExtendedSlot: false, clickedCount: 0, message: e.message });
                }
            })();
        """.trimIndent()
    }

    /**
     * Checks current slip state (checked cells count across A-D) and optionally clicks a
     * non-purchasing cart/add-slip button ("Sepete Ekle" / "Kupona Ekle" / "Yeni Kupon") if present.
     */
    fun buildSlipAdvanceOrStateScript(clickCartOrNewSlip: Boolean = false): String {
        return """
            (function() {
                try {
                    var checkedCount = 0;
                    for (var slot = 0; slot < 4; slot++) {
                        for (var m = 0; m < 15; m++) {
                            for (var o = 0; o < 3; o++) {
                                var cell = document.getElementById("m-c-" + m + "-" + slot + "-" + o);
                                if (cell && cell.checked) checkedCount++;
                            }
                        }
                    }

                    var advancedToCart = false;
                    if ($clickCartOrNewSlip) {
                        var btns = document.querySelectorAll("button, a[role='button'], input[type='button']");
                        for (var i = 0; i < btns.length; i++) {
                            var r = btns[i].getBoundingClientRect();
                            if (r.width <= 0 || r.height <= 0) continue;
                            var t = (btns[i].innerText || btns[i].value || "").trim().toLowerCase();
                            if (t === "sepete ekle" || t === "kupona ekle" || t === "yeni kupon" || t === "yeni kupon ekle" || t === "kolon ekle") {
                                btns[i].click();
                                advancedToCart = true;
                                break;
                            }
                        }
                    }

                    var bodyText = (document.body && document.body.innerText ? document.body.innerText : "").toLowerCase();
                    var hasSuccessNotice =
                        bodyText.indexOf("kuponunuz oynan") !== -1 ||
                        bodyText.indexOf("oynama işleminiz gerçekleş") !== -1 ||
                        bodyText.indexOf("kuponunuz onaylan") !== -1;

                    return JSON.stringify({
                        checkedCount: checkedCount,
                        advancedToCart: advancedToCart,
                        hasSuccessNotice: hasSuccessNotice
                    });
                } catch (e) {
                    return JSON.stringify({ checkedCount: -1, advancedToCart: false, hasSuccessNotice: false, error: e.message });
                }
            })();
        """.trimIndent()
    }

    /**
     * Triggers Nesine's play button ("Hemen Oyna" / "Oyna") or an open confirmation dialog ("Onayla")
     * ONLY if the member session is active; otherwise opens login prompt and reports NOT_LOGGED_IN.
     */
    fun buildPlayAndConfirmScript(): String {
        return """
            (function() {
                try {
                    var bodyText = (document.body && document.body.innerText ? document.body.innerText : "").toLowerCase();
                    if (bodyText.indexOf("bakiye yetersiz") !== -1 ||
                        bodyText.indexOf("yetersiz bakiye") !== -1 ||
                        bodyText.indexOf("bakiyeniz yetersiz") !== -1 ||
                        bodyText.indexOf("yeterli bakiye bulunm") !== -1) {
                        return JSON.stringify({
                            status: "INSUFFICIENT_BALANCE",
                            clicked: false,
                            message: "Nesine hesabınızda bakiye yetersiz görünüyor."
                        });
                    }
                    if (bodyText.indexOf("sms şifresi") !== -1 ||
                        bodyText.indexOf("sms doğrulama") !== -1 ||
                        bodyText.indexOf("doğrulama kodu") !== -1 ||
                        bodyText.indexOf("tek kullanımlık") !== -1) {
                        return JSON.stringify({
                            status: "REQUIRES_2FA",
                            clicked: false,
                            message: "Nesine SMS doğrulama (2FA) kodu bekliyor; kodu ekrandan girin."
                        });
                    }

                    var passInputs = document.querySelectorAll("input[type='password']");
                    for (var p = 0; p < passInputs.length; p++) {
                        var pr = passInputs[p].getBoundingClientRect();
                        if (pr.width > 0 && pr.height > 0) {
                            return JSON.stringify({
                                status: "NOT_LOGGED_IN",
                                clicked: false,
                                message: "Nesine giriş formu açık! Lütfen giriş işlemini tamamlayın."
                            });
                        }
                    }

                    // Dismiss cookie consent overlay first if present so play/confirm/login clicks work cleanly
                    var cookieBtns = document.querySelectorAll("#onetrust-accept-btn-handler, button, a");
                    for (var c = 0; c < cookieBtns.length; c++) {
                        var cr = cookieBtns[c].getBoundingClientRect();
                        if (cr.width <= 0 || cr.height <= 0) continue;
                        var ct = (cookieBtns[c].innerText || "").trim().toLowerCase();
                        if (cookieBtns[c].id === "onetrust-accept-btn-handler" || ct === "kabul et" || ct === "tümünü kabul et") {
                            cookieBtns[c].click();
                            break;
                        }
                    }

                    // Check login state before clicking Onayla or Hemen Oyna
                    var headerEl = document.querySelector("header, nav, .header, [class*='header' i], [class*='navbar' i]");
                    var headerText = (headerEl && headerEl.innerText ? headerEl.innerText : bodyText.slice(0, 1500)).toLowerCase();
                    var hasBalanceEl = !!document.querySelector(
                        "[class*='balance' i], [class*='bakiye' i], [data-testid*='balance' i], .user-balance, .wallet-amount, a[href*='hesabim'], a[href*='para-yatir']"
                    );
                    var hasLoggedInText =
                        headerText.indexOf("hesabım") !== -1 ||
                        headerText.indexOf("hesabim") !== -1 ||
                        headerText.indexOf("çıkış") !== -1 ||
                        headerText.indexOf("cikis") !== -1 ||
                        headerText.indexOf("para yatır") !== -1 ||
                        headerText.indexOf("kuponlarım") !== -1;

                    var visibleLoginTrigger = null;
                    var headerBtns = document.querySelectorAll("button, a");
                    for (var hb = 0; hb < headerBtns.length; hb++) {
                        var hbr = headerBtns[hb].getBoundingClientRect();
                        if (hbr.width <= 0 || hbr.height <= 0) continue;
                        var hbt = (headerBtns[hb].innerText || "").trim().toLowerCase();
                        if (hbt === "giriş yap" || hbt === "giris yap" || hbt === "üye girişi") {
                            visibleLoginTrigger = headerBtns[hb];
                            break;
                        }
                    }
                    if (!visibleLoginTrigger) {
                        var loginButtons = document.querySelectorAll(
                            "[data-testid='login-button'], .login-btn, .header-login-btn, button[class*='login' i], a[class*='login' i]"
                        );
                        for (var i = 0; i < loginButtons.length; i++) {
                            var lr = loginButtons[i].getBoundingClientRect();
                            if (lr.width > 0 && lr.height > 0) {
                                visibleLoginTrigger = loginButtons[i];
                                break;
                            }
                        }
                    }

                    if (visibleLoginTrigger && !hasBalanceEl && !hasLoggedInText) {
                        visibleLoginTrigger.click();
                        return JSON.stringify({
                            status: "NOT_LOGGED_IN",
                            clicked: false,
                            message: "Nesine oturumu açılmamış! Giriş penceresi açıldı, önce oturum açın."
                        });
                    }

                    // 1) Check if a confirmation modal is ALREADY open ("Onayla" / "Kuponu Onayla" / "Evet")
                    var modalBtns = document.querySelectorAll(".modal button, [role='dialog'] button, [class*='modal' i] button, [class*='popup' i] button, [class*='dialog' i] button");
                    for (var m = 0; m < modalBtns.length; m++) {
                        var mRect = modalBtns[m].getBoundingClientRect();
                        if (mRect.width <= 0 || mRect.height <= 0) continue;
                        var mTxt = (modalBtns[m].innerText || "").trim().toLowerCase();
                        if (mTxt === "onayla" || mTxt === "kuponu onayla" || mTxt === "oynamayı onayla" || mTxt === "evet" || mTxt === "onaylıyorum" || mTxt === "hemen oyna") {
                            modalBtns[m].click();
                            return JSON.stringify({
                                status: "CONFIRMED",
                                clicked: true,
                                message: "Oynama onayı tıklandı: " + modalBtns[m].innerText.trim()
                            });
                        }
                    }

                    // 2) Check inline / footer page-level confirmation buttons (e.g. mobile review bar: Misli / Kupon Tutarı / < / Onayla)
                    var pageBtns = document.querySelectorAll("button, a[role='button'], input[type='button'], input[type='submit'], a");
                    for (var pb = 0; pb < pageBtns.length; pb++) {
                        var pbEl = pageBtns[pb];
                        if (pbEl.disabled) continue;
                        var pbr = pbEl.getBoundingClientRect();
                        if (pbr.width <= 0 || pbr.height <= 0) continue;
                        var pbTxt = (pbEl.innerText || pbEl.value || "").trim().toLowerCase();
                        if (pbTxt === "onayla" || pbTxt === "kuponu onayla" || pbTxt === "oynamayı onayla" || pbTxt === "onaylıyorum") {
                            pbEl.scrollIntoView({ behavior: "smooth", block: "center" });
                            pbEl.click();
                            return JSON.stringify({
                                status: "CONFIRMED",
                                clicked: true,
                                message: "Kupon onayı tıklandı: " + (pbEl.innerText || pbEl.value || "Onayla").trim()
                            });
                        }
                    }

                    // 3) Locate the primary visible Play ("Hemen Oyna" / "Oyna") button
                    var playBtn = null;
                    var selectorPlayBtns = document.querySelectorAll(
                        "[data-testid='submit-coupon'], button[class*='hemen-oyna' i], button[class*='play' i], button[class*='oyna' i], a[class*='oyna' i]"
                    );
                    for (var sp = 0; sp < selectorPlayBtns.length; sp++) {
                        var spr = selectorPlayBtns[sp].getBoundingClientRect();
                        if (spr.width > 0 && spr.height > 0 && !selectorPlayBtns[sp].disabled) {
                            playBtn = selectorPlayBtns[sp];
                            break;
                        }
                    }
                    if (!playBtn) {
                        var allBtns = document.querySelectorAll("button, a[role='button'], input[type='button'], input[type='submit']");
                        for (var b = 0; b < allBtns.length; b++) {
                            var br = allBtns[b].getBoundingClientRect();
                            if (br.width <= 0 || br.height <= 0 || allBtns[b].disabled) continue;
                            var bTxt = (allBtns[b].innerText || allBtns[b].value || "").trim().toLowerCase();
                            if (bTxt === "hemen oyna" || bTxt === "kuponu oyna" || bTxt === "oyna" || bTxt === "kupon yatır") {
                                playBtn = allBtns[b];
                                break;
                            }
                        }
                    }

                    if (!playBtn) {
                        return JSON.stringify({
                            status: "NO_PLAY_BUTTON",
                            clicked: false,
                            message: "Nesine sayfasında 'Hemen Oyna / Oyna' butonu bulunamadı. Kolonları kontrol edip sayfayı aşağı kaydırın."
                        });
                    }

                    playBtn.scrollIntoView({ behavior: "smooth", block: "center" });
                    playBtn.click();

                    return JSON.stringify({
                        status: "PLAY_CLICKED",
                        clicked: true,
                        message: "Oynama talebi gönderildi — Nesine onay penceresini kontrol edin."
                    });
                } catch (e) {
                    return JSON.stringify({ status: "ERROR", clicked: false, message: e.message });
                }
            })();
        """.trimIndent()
    }
}
