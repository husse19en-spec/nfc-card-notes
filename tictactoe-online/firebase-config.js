// ضع هنا بيانات مشروع Firebase الخاص بك
// (Firebase Console ▸ Project settings ▸ General ▸ Your apps ▸ Web app ▸ SDK setup and configuration)
export const firebaseConfig = {
  apiKey: "PASTE_YOUR_API_KEY",
  authDomain: "YOUR_PROJECT.firebaseapp.com",
  projectId: "YOUR_PROJECT",
  storageBucket: "YOUR_PROJECT.appspot.com",
  messagingSenderId: "",
  appId: "",
};

// خوادم STUN/TURN للمكالمات الصوتية.
// STUN المجاني يكفي لمعظم الشبكات، لكن بعض شبكات الجوال/الشركات تحتاج خادم TURN
// (مثل Metered أو Twilio أو coturn خاص بك). أضفه هنا إن تعذّر الاتصال الصوتي.
export const iceServers = [
  { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
  // { urls: "turn:YOUR_TURN_HOST:3478", username: "user", credential: "pass" },
];
