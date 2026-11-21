# mom-tel — สถานะงาน

อัปเดตล่าสุด: 21 พ.ย. 2026

## ทำอะไรไปแล้ว (ล่าสุด)
- หน้าแป้นกดตัวเลข: เปลี่ยนลูกศรย้อนกลับเล็ก ๆ มุมซ้ายบน เป็นปุ่ม "กลับหน้าหลัก" ใหญ่เต็มความกว้าง (สูง 68dp สีเหลืองอ่อนขอบส้ม)
  วางไว้ด้านบน ไกลจากปุ่มโทรสีเขียว เพื่อไม่ให้แม่กดพลาด และเอาหัวข้อ "กดเบอร์โทรศัพท์" ออกเพื่อไม่ให้แป้นตัวเลขเล็กลง

- build + unit test ผ่าน และติดตั้งลงมือถือแม่ (Infinix X6840, v1.1.3) แล้ว 21 พ.ย. 2026
  ทดสอบบนเครื่องจริง: เปิดแป้นกด → กด "กลับหน้าหลัก" → กลับหน้าสมุดโทรศัพท์ถูกต้อง

- ติดตั้ง Whoscall (v8.46, แบบฟรี) บนมือถือแม่เป็น "แอปบอกชื่อผู้โทร" (role `CALL_SCREENING`)
  แอปนี้ยังเป็นแอปโทรหลัก (role `DIALER` = `com.elderphone.app`) — ห้ามให้ Whoscall ยึด role DIALER
  ทดสอบแล้ว: สายเข้าจากเบอร์ทดสอบ (บันทึกในเครื่อง) ขึ้นหน้ารับสายของแอปปกติ ไม่มีป้ายบังปุ่ม ขณะอยู่ในโหมดล็อก
  ระบบบล็อกเบอร์ของแอป (`CallService` → `BlockedNumberManager`) ทำงานแยกจาก Whoscall จึงยังใช้ได้
- ตัดสินใจ: ใช้ Whoscall แบบฟรี + ให้กด "บล็อกเบอร์นี้" บนหน้ารับสายเองเมื่อป้ายบอกว่าเป็นสายขายของ/ก่อกวน
  (บล็อกอัตโนมัติเป็นฟีเจอร์ Premium ~27 USD/ปี; แอปนี้อ่านผลของ Whoscall ไม่ได้ เพราะ Android ไม่ส่งป้ายจาก
  call-screening app ไปให้ dialer — ทางอ้อมด้วย NotificationListener ถูกปฏิเสธเพราะเปราะและต้องอ่านแจ้งเตือนทั้งเครื่อง)

## ค้าง / ติดอะไร
- ยังไม่ได้ให้แม่ลองใช้ปุ่ม "กลับหน้าหลัก" เองว่ากดง่ายขึ้นจริงไหม
- ยังไม่ได้ทดสอบป้ายของ Whoscall ตอน "เบอร์ไม่รู้จัก" โทรเข้า (บังปุ่มรับสายไหม / ขึ้นตอนโหมดล็อกไหม) — รอเบอร์แปลกโทรมาจริง

## งานถัดไป
- รอความเห็นจากแม่หลังใช้งานจริง
- เมื่อเบอร์แปลกโทรเข้า: ถ่ายภาพหน้าจอตอนดังเพื่อตรวจตำแหน่งป้าย Whoscall (ลบภาพทิ้งหลังดู — มีข้อมูลส่วนตัว)
- ถ้าสายขายของเยอะ: พิจารณาซื้อ Whoscall Premium เพื่อบล็อกอัตโนมัติ

## กับดักที่เจอมาแล้ว
- Mac เครื่องนี้ไม่มี Android Studio และ `java` ของระบบหา runtime ไม่เจอ แต่มี JDK 17 + Android SDK จาก Homebrew
  ต้องตั้ง env ก่อน build:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
  แล้ว `./gradlew --offline assembleDebug testDebugUnitTest`
- ลงมือถือผ่าน Wi-Fi (ไม่ต้องเสียบสาย, มือถือต้องอยู่ network เดียวกันและเปิด Wireless debugging ไว้ — จับคู่ไว้แล้ว):
  `adb mdns services` ดู IP:port → `adb connect <IP:port>` → `adb -s <IP:port> install -r app/build/outputs/apk/debug/app-debug.apk`
  port เปลี่ยนทุกครั้งที่มือถือเปิด Wireless debugging ใหม่ ต้องดูจาก `adb mdns services` ทุกครั้ง

- คำสั่งตรวจ role บนมือถือ: `adb -s <IP:port> shell cmd role get-role-holders android.app.role.DIALER` (และ `.CALL_SCREENING`)
- `rm` กับตัวแปร path ใน Claude Code ต้องเขียน `"${D:?}"/*.png` ไม่อย่างนั้นถูกบล็อก

## ตั้งเครื่องใหม่
- ติดตั้ง Android Studio (มี JDK ในตัว) + Android SDK, เปิดโฟลเดอร์โปรเจกต์, เปิด USB debugging บนมือถือ
- ไม่มีไฟล์ลับหรือ `.env` ที่ต้องคัดลอกตามไป
