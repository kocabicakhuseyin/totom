# Spor Toto 15'li sonuç otomasyonu

Kaynak: https://totocover.com/ (sonuçlandı olarak işaretlenen program),
`https://totocover.com/bulten/arsiv/YYYY/PROGRAM`.

## Sistem

- `.github/workflows/weekly-spor-toto-results.yml`: Pazartesi ve Salı **09:00 Türkiye saati** otomatik kontrol; GitHub Actions çalışma zamanı gecikebilir.
- `scripts/fetch_weekly_results.py`: Kaynak HTML'deki programı ve 15 maçın tamamını ayrıştırır. Tamamlanmış 15 maç yoksa, maç sıralaması bozuksa veya site şablonu değişirse hata ile çıkar ve önceden kaydedilmiş JSON'u korur.
- `data/last_completed_spor_toto.json`: İlk başarılı otomatik çalışmada oluşturulur. GitHub Actions bunu depoya commit eder; uygulama JSON'u GitHub Raw üzerinden okur.
- Android sonuç ekranı: ESPN/API-Football'dan rastgele 15 maç toplamak yerine arşivdeki *gerçek bülten sırasını* okur. Veri değişince **yeni APK gerekmeksizin** uygulama ekranı yeniden açıldığında/yüklendiğinde güncellenir.

## İlk çalıştırma

1. Bu paketteki dosyaları **kocabicakhuseyin/totom** deposunun `main` dalına yükleyin. Bu düzenlemeler bu sohbetten otomatik olarak GitHub'a gönderilmedi.
2. GitHub > Actions > **Spor Toto haftalik kesinlesen sonuclar** > **Run workflow** ile ilk çalıştırmayı başlatın.
3. `data/last_completed_spor_toto.json` oluştuğunu ve tam 15 maç içerdiğini doğrulayın.
4. Android uygulamanın bu kaynak güncellemesini içeren APK'sını bir defa dağıtın. Sonraki haftalarda sadece JSON yenilenir.
5. `raw.githubusercontent.com/kocabicakhuseyin/totom/main/...` adresi anonim okunabilir olmalıdır. Depo gizliyse, **yetkili herkese açık bir JSON/CDN endpointi** kullanın ve `TotoCoverResultsFeed.kt` içindeki `FEED_URL` adresini değiştirin; erişim tokenını APK'ye gömmeyin.
6. GitHub Actions'ın repository üzerinde `contents: write` izni ve commit yetkisi olmalıdır. Kurum kuralları korumalı dala push'a izin vermiyorsa, özel bir veriler dalı/PR iş akışı yapılandırın.

## Önemli

- Arşiv **TotoCover** kaynaklıdır, resmî Spor Toto doğrulaması anlamına gelmez.
- Kaynak site otomatize istekleri engellerse veya HTML değişirse iş akışı başarısız olur; eski doğrulanmış veri korunur. Erişim engelini aşma mekanizması yoktur.
- GitHub zamanlaması, maçı biten her hafta sonu için *en son sonuçlanmış programı* seçer; henüz bitmemiş program yüklenmez.
- Kaynak veriler ücretsiz ve otomatik erişime uygun tutulmalıdır; sitenin kullanım şartları ve istek sınırları dikkate alınmalıdır.
- Yerel testler: `python -m pip install requests beautifulsoup4` ve `cd scripts && python -m unittest -v test_fetch_weekly_results.py`.
- Canlı siteye yönelik bir çalışma bu ortamda ağ/DNS engeli nedeniyle yürütülemedi; HTML ayrıştırıcı testleri sentetik örneklerle çalıştırıldı. Android tam derlemesi burada çalıştırılmadı.
