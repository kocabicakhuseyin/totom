# Totom - Android APK derleme

GitHub > Actions > Android Debug APK > Run workflow yoluyla derleme başlatın.
Tamamlanan işlemde Artifacts bölümündeki `Totom-debug-apk` dosyasını indirin.

Bu paket SDK android-36 kurulumu, API 36 compile ayarı, Gradle 9.3.1 ve derleme öncelikli iş akışı ile güncellenmiştir. Gradle Wrapper dosyaları projede olmadığı için GitHub Actions `setup-gradle` ile Gradle sağlar. Yerel ortamda `./gradlew` çalışmaz; `gradle` komutunu kullanın ya da wrapper üretin.

NOT: Gerçek GitHub derleme sonucu bu ortamda doğrulanamamıştır. Derleme hata verirse Actions > başarısız run > Build debug APK logunun ilk `What went wrong` bölümüne bakın.
