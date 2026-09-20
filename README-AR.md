# Shorts + Native yt-dlp — مشروع جاهز للبناء

هذا المشروع يحوّل ملف HTML المرفق إلى تطبيق Capacitor Android، ويضيف محرك yt-dlp داخل APK.

## ترتيب التنزيل

1. **Native yt-dlp** داخل التطبيق.
2. **خادم خاص** إذا وضعت رابطه في `localStorage` بالمفتاح `shorts_private_server_url`.
3. **Cobalt** كمسار احتياطي تجريبي.
4. **Invidious** كمسار احتياطي.
5. حفظ رابط YouTube للمشاهدة إذا فشلت المسارات السابقة.

## ملاحظات مهمة

- المكتبة الحالية `yt-dlp-android 2.0.2` تتطلب Android 7+ وتدعم arm64-v8a وx86_64 فقط.
- المكتبة كبيرة لأنها تحتوي Python 3.13 وyt-dlp داخل AAR.
- لم يتم تضمين مجلد `android/` المولّد يدويًا؛ سكربت `setup-android.mjs` ينشئه بواسطة Capacitor ثم يدمج البلجن تلقائيًا. هذا يجعل المشروع أسهل للبناء على GitHub Actions.
- لم أعتبر نجاح البناء في هذه البيئة اختبارًا فعليًا على جهاز Android؛ يجب أن يتم البناء في GitHub Actions أو Android Studio.

## البناء محليًا

```bash
npm install
npm run cap:add:android
npm run android:setup
npm run cap:sync
cd android
./gradlew assembleDebug
```

## البناء على GitHub

ارفع المشروع كاملًا إلى GitHub ثم شغّل:

`Actions -> Build Android APK -> Run workflow`

سيتم تثبيت Capacitor، إنشاء مجلد Android، دمج البلجن، ثم بناء APK.

## خادم المنزل / Tailscale

بعد تشغيل خادم التنزيل على جهاز المنزل، ضع عنوانه في التطبيق:

```js
localStorage.setItem('shorts_private_server_url', 'http://100.x.x.x:8787');
```

ويجب أن يقدم الخادم:

`POST /download`

ويرجع JSON مثل:

```json
{"url":"https://.../video.mp4"}
```

استخدم التنزيل فقط للمحتوى الذي لديك الحق في تنزيله ووفق شروط الخدمة.
