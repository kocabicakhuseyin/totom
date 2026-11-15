# Totom - Android APK derleme

GitHub > Actions > Android Debug APK > Run workflow yoluyla derleme başlatın.
Tamamlanan işlemde Artifacts bölümündeki `Totom-debug-apk` dosyasını indirin.

Bu paket SDK android-36 kurulumu, API 36 compile ayarı, Gradle 9.3.1 ve derleme öncelikli iş akışı ile güncellenmiştir. Gradle Wrapper dosyaları projede olmadığı için GitHub Actions `setup-gradle` ile Gradle sağlar. Yerel ortamda `./gradlew` çalışmaz; `gradle` komutunu kullanın ya da wrapper üretin.

NOT: Gerçek GitHub derleme sonucu bu ortamda doğrulanamamıştır. Derleme hata verirse Actions > başarısız run > Build debug APK logunun ilk `What went wrong` bölümüne bakın.

## 2026-11-16 test düzeltmesi
- SupabaseMembershipTest Robolectric JVM testi API 36 yerine API 35 ile çalışır; uygulamanın compileSdk/targetSdk değeri değiştirilmedi.
- Önce APK derlenir ve GitHub Actions artifact olarak yüklenir; sonra testler çalışır. Test hata verirse HTML raporu ayrı artifact olarak yüklenir.
- Kaynak logda assembleDebug başarılı, 91 testten biri başarısızdı. GitHub üzerinde yeni çalıştırma doğrulaması henüz yapılmadı.
