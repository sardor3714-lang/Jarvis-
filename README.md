# J.A.R.V.I.S. — Android uchun ovozli yordamchi

API kalitsiz ishlaydi. Ovozni telefonning o'zidagi Google xizmati taniydi, javoblarni telefonning ovoz sintezatori (TTS) o'qiydi. Internetdan esa kalit talab qilmaydigan ochiq manbalar ishlatiladi: ob-havo uchun wttr.in, ma'lumot uchun Vikipediya.

## Nimalar qila oladi

| Buyruq (misol) | Nima qiladi |
|---|---|
| Telegramni och / open YouTube | Istalgan o'rnatilgan ilovani ochadi |
| Fonarni yoq / fonarni o'chir | Telefon chirog'i |
| Soat nechi? / Bugun nechanchi sana? | Vaqt va sana |
| Soat 7 da budilnik qo'y | Budilnik |
| 5 daqiqaga taymer qo'y | Taymer |
| Onamga qo'ng'iroq qil | Kontaktdan topib qo'ng'iroq qiladi |
| Samarqandda ob-havo qanday? | Ob-havo (internet) |
| Ibn Sino kim? / Kvant kompyuter nima? | Vikipediyadan qisqacha ma'lumot |
| Google'da qidir sun'iy intellekt | Google qidiruvi |
| YouTube'da Iron Man qo'y | YouTube qidiruvi |
| 25 ko'paytir 4 | Hisob-kitob |
| Ovozni balandlat / pasaytir / o'chir | Ovoz balandligi |
| Wi-Fi / Bluetooth / mobil internet | Tegishli panelni ochadi |
| Batareya necha foiz? | Zaryad darajasi |
| Aliga sms yoz salom qalaysan | SMS yuboradi (avval tasdiq so'raydi) |
| Onamga SMS yoz | Kimga va nima deb yozishni o'zi so'rab oladi |
| Eslab qol, ertaga imtihon bor | Eslab qoladi, keyin suhbatda hisobga oladi |
| Nimalarni eslab qolding? | Eslab qolganlarini aytadi |
| Mening ismim ... | Ismingizni eslab qoladi |
| Istalgan savol yoki gap | AI bilan erkin suhbat |
| Nima qila olasan? | Buyruqlar ro'yxati |

## Aql (v2)
Oddiy buyruqlarni Jarvis telefonning o'zida tushunadi. Qolgan hamma gap kalitsiz, bepul AI xizmatlariga yuboriladi: avval LLM7, u ishlamasa Pollinations. AI ikki ish qiladi:
- erkin suhbatlashadi va savollarga javob beradi;
- erkin aytilgan gapni Jarvis buyrug'iga aylantiradi. Masalan, "menga biror qo'shiq qo'yib ber" desangiz, YouTube'da musiqa ochadi.

Jarvis savol bersa yoki AI javob bersa, gapirib bo'lgach mikrofonni o'zi yoqadi, shuning uchun suhbat uzilmaydi. Agar AI serverlari javob bermasa, Jarvis Vikipediya va Google'dan izlaydi.

**Maxfiylik:** erkin suhbatdagi gaplaringiz va eslab qolgan narsalaringiz shu bepul AI xizmatlariga yuboriladi. Parol, karta raqami kabi maxfiy ma'lumotlarni aytmang.

## Jarvisni tez chaqirish

1. **📌 tugmasi.** Bildirishnomalar panelida doimiy "Jarvis" tugmasi paydo bo'ladi. Bossangiz, Jarvis darhol tinglay boshlaydi.
2. **Tezkor sozlamalar plitkasi.** Bildirishnomalar panelini to'liq tushiring, ✏️ (tahrirlash) tugmasini bosing va "Jarvis" plitkasini yuqoriga sudrab qo'ying.
3. **Assistent sifatida.** Sozlamalar → Ilovalar → Standart ilovalar → Raqamli yordamchi bo'limida Jarvisni tanlang (ba'zi telefonlarda bu imkoniyat bor). Shundan keyin Home tugmasini uzoq bossangiz, Jarvis ochiladi.

## APK olish

### A) GitHub orqali, kompyutersiz
1. GitHub'da yangi repozitoriy oching va shu papkadagi barcha fayllarni yuklang (`.github` papkasi ham bo'lishi shart).
2. **Actions** bo'limida "Jarvis APK" jarayoni o'zi ishga tushadi. U 3–5 daqiqa davom etadi.
3. Tayyor bo'lgach, **Releases** bo'limida `Jarvis.apk` paydo bo'ladi. Uni telefoningizda oching va o'rnating.

### B) Android Studio orqali
1. Android Studio'da **File → Open** orqali shu papkani oching.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)** ni tanlang.
3. Tayyor fayl shu yerda bo'ladi: `app/build/outputs/apk/debug/app-debug.apk`

## O'rnatish
- Telefoningiz "noma'lum manbalardan o'rnatish"ga ruxsat so'raydi, ruxsat bering.
- Google Play Protect ogohlantirish berishi mumkin: "Baribir o'rnatish" tugmasini bosing. Bu ilova Play Market'dan emasligi uchun shunday bo'ladi.
- Birinchi ishga tushirishda mikrofon va bildirishnomalarga ruxsat bering.

## Cheklovlar
- **O'zbek tilida ovozli buyruq** telefoningizdagi Google ilovasi o'zbek tilini qo'llashiga bog'liq. Agar o'zbekcha tanimasa, **EN** tugmasini bosib, ingliz tilida gapiring.
- **O'zbekcha ovozli javob:** ko'p telefonlarda o'zbek TTS ovozi yo'q. Bunday holatda Jarvis turkcha ovozdan foydalanadi, matn esa ekranda to'g'ri chiqadi.
- **Wi-Fi va mobil internet:** Android 10 dan boshlab ilovalar ularni o'zi yoqib-o'chira olmaydi. Shuning uchun Jarvis tegishli panelni ochib beradi, siz bir marta bosasiz.
- **"Hey Jarvis" deb uyg'otish** hozircha yo'q. Uning uchun mikrofon doim yoqiq turishi kerak, bu esa batareyani tez yeydi. Uning o'rniga 📌 tugmasi yoki plitkadan foydalaning.
