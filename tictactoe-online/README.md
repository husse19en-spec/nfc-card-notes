# إكس أو أونلاين ❌⭕

لعبة إكس أو (Tic‑Tac‑Toe) تُلعب أونلاين بين شخصين، مع:

- **تسجيل الدخول بحساب Gmail** (Google Sign‑In عبر Firebase Authentication).
- **غرف لعب**: أنشئ غرفة وأرسل الرمز أو الرابط لصديقك، أو انضم لغرفة من قائمة الغرف المتاحة.
- **دردشة نصية** فورية داخل الغرفة.
- **مكالمة صوتية** مباشرة بين اللاعبَين (WebRTC، والصوت يمر مباشرة بين الجهازين).
- عدّاد للنتائج، وجولات متتالية مع تبادل من يبدأ اللعب.

تطبيق ويب بسيط (HTML + JavaScript) بدون أي خطوة بناء، ويستخدم Firebase كخادم.

## الإعداد (مرة واحدة، مجاناً)

1. ادخل إلى <https://console.firebase.google.com> وأنشئ مشروعاً جديداً.
2. **Authentication** ▸ Get started ▸ Sign-in method ▸ فعّل **Google**.
3. **Firestore Database** ▸ Create database (اختر أي منطقة، ووضع Production).
4. من **Project settings** ▸ General ▸ Your apps ▸ اضغط أيقونة الويب `</>` وسجّل تطبيقاً،
   ثم انسخ قيم `firebaseConfig` والصقها في الملف [`firebase-config.js`](firebase-config.js).
5. انشر قواعد الحماية: انسخ محتوى [`firestore.rules`](firestore.rules) إلى
   Firestore ▸ Rules ثم اضغط **Publish** (أو استخدم أمر النشر في الأسفل).

## النشر على الإنترنت

### الطريقة الأسهل: Firebase Hosting
```bash
npm install -g firebase-tools
cd tictactoe-online
firebase login
firebase use --add          # اختر مشروعك
firebase deploy             # ينشر الموقع + قواعد Firestore
```
ستحصل على رابط مثل `https://YOUR_PROJECT.web.app` — أرسله لأصدقائك.

### أو أي استضافة أخرى (GitHub Pages / Netlify …)
ارفع محتويات المجلد كما هي، ثم أضف نطاق موقعك في
Authentication ▸ Settings ▸ **Authorized domains** وإلا سيفشل تسجيل الدخول.

## التجربة على جهازك
```bash
cd tictactoe-online
python3 -m http.server 8000
```
ثم افتح <http://localhost:8000> (نطاق `localhost` مسموح افتراضياً في Firebase).
لتجربة اللعب بين لاعبَين افتح نافذة عادية وأخرى خاصة (Incognito) بحسابَي Gmail مختلفين.

> المكالمة الصوتية تحتاج **HTTPS** (أو localhost) وإذن الميكروفون من المتصفح.

## ملاحظات
- يستخدم الاتصال الصوتي خوادم STUN المجانية من Google، وهي تكفي لمعظم الشبكات.
  إن ظهرت رسالة "تعذّر الاتصال الصوتي" على بعض شبكات الجوال أو الشركات، أضف خادم
  **TURN** في `firebase-config.js`.
- قيم `firebaseConfig` ليست سرية (تظهر في أي موقع يستخدم Firebase)؛ الحماية تأتي من
  قواعد `firestore.rules`.
- الغرف التي تنتظر لاعباً لأكثر من ساعتين لا تظهر في القائمة.

## الملفات
| الملف | الوظيفة |
|---|---|
| `index.html` | واجهة اللعبة |
| `style.css` | التصميم |
| `app.js` | منطق تسجيل الدخول، الغرف، اللعب، الدردشة، والمكالمة الصوتية |
| `firebase-config.js` | إعدادات مشروع Firebase وخوادم الصوت |
| `firestore.rules` | قواعد حماية قاعدة البيانات |
| `firebase.json` | إعداد النشر على Firebase Hosting |
