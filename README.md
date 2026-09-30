# Next Alarm SystemUI

Android 16 / Project Infinity için LSPosed modülü.

## Ne yapar?
- Hızlı Ayarlar başlığındaki saat/tarih alanına `⏰ 06:30` biçiminde sonraki alarmı ekler.
- Kilit ekranındaki üst saat alanında da aynı alarm bilgisini gösterir.
- Alarm kurulu değilse alarm yazısını tamamen gizler.
- Alarm değiştiğinde SystemUI yeniden başlatılmadan güncellenmeye çalışır.

## Derleme (GitHub Actions)
1. Bu projenin içeriğini yeni bir GitHub deposuna yükleyin.
2. **Actions > Build APK > Run workflow** seçin.
3. İşlem bitince **Artifacts > NextAlarmSystemUI-debug** dosyasını indirin.
4. APK'yı kurun.
5. LSPosed'da modülü etkinleştirin. Kapsam yalnızca **System UI / com.android.systemui** olmalı.
6. SystemUI'yi veya telefonu yeniden başlatın.

## Not
Project Infinity'nin SystemUI sınıf/ID adları AOSP'tan çok farklıysa ilk sürümde konum bulunamayabilir. Bu durumda LSPosed loguna göre hedefi ROM'a özel sabitleriz.
