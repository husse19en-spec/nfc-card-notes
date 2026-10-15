import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, onAuthStateChanged,
  signInWithPopup, signInWithRedirect, signOut,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import {
  getFirestore, collection, doc, getDoc, getDocs, setDoc, addDoc, updateDoc, deleteDoc,
  onSnapshot, query, where, orderBy, limit, limitToLast, runTransaction,
  serverTimestamp, arrayUnion, writeBatch,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { firebaseConfig, iceServers } from "./firebase-config.js";

const $ = (id) => document.getElementById(id);
const screens = ["setupScreen", "loginScreen", "lobbyScreen", "roomScreen"];
function show(screen) {
  screens.forEach((s) => $(s).classList.toggle("hidden", s !== screen));
}

let toastTimer;
function toast(msg) {
  const t = $("toast");
  t.textContent = msg;
  t.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), 3500);
}

if (!firebaseConfig.apiKey || firebaseConfig.apiKey.startsWith("PASTE_")) {
  show("setupScreen");
  throw new Error("firebase-config.js is not filled in");
}

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);

let me = null;          // { uid, name, photo }
let roomId = null;
let room = null;        // آخر نسخة من مستند الغرفة
let roomUnsubs = [];
let lobbyUnsub = null;

const WIN_LINES = [
  [0, 1, 2], [3, 4, 5], [6, 7, 8],
  [0, 3, 6], [1, 4, 7], [2, 5, 8],
  [0, 4, 8], [2, 4, 6],
];

function checkWinner(board) {
  for (const line of WIN_LINES) {
    const [a, b, c] = line;
    if (board[a] && board[a] === board[b] && board[a] === board[c]) {
      return { winner: board[a], line };
    }
  }
  if (board.every(Boolean)) return { winner: "draw", line: [] };
  return null;
}

/* ---------------- تسجيل الدخول ---------------- */

$("signInBtn").onclick = async () => {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  try {
    await signInWithPopup(auth, provider);
  } catch (e) {
    if (e.code === "auth/popup-blocked" || e.code === "auth/operation-not-supported-in-this-environment") {
      await signInWithRedirect(auth, provider);
    } else if (e.code !== "auth/popup-closed-by-user" && e.code !== "auth/cancelled-popup-request") {
      toast("تعذّر تسجيل الدخول: " + e.message);
    }
  }
};

$("signOutBtn").onclick = async () => {
  await leaveRoom();
  await signOut(auth);
};

onAuthStateChanged(auth, (user) => {
  if (!user) {
    me = null;
    $("userBox").classList.add("hidden");
    stopLobby();
    show("loginScreen");
    return;
  }
  me = {
    uid: user.uid,
    name: user.displayName || user.email.split("@")[0],
    photo: user.photoURL || "",
  };
  $("userName").textContent = me.name;
  if (me.photo) $("userPhoto").src = me.photo; else $("userPhoto").removeAttribute("src");
  $("userBox").classList.remove("hidden");

  const code = new URLSearchParams(location.search).get("room");
  if (code) joinRoom(code);
  else enterLobby();
});

/* ---------------- الردهة ---------------- */

function enterLobby() {
  show("lobbyScreen");
  setUrlRoom(null);
  if (lobbyUnsub) return;
  const q = query(collection(db, "rooms"), where("status", "==", "waiting"), limit(30));
  lobbyUnsub = onSnapshot(q, (snap) => {
    const list = $("openRooms");
    list.innerHTML = "";
    const twoHoursAgo = Date.now() - 2 * 60 * 60 * 1000;
    const rooms = snap.docs
      .map((d) => ({ id: d.id, ...d.data() }))
      .filter((r) => (r.createdAt?.toMillis?.() ?? Date.now()) > twoHoursAgo)
      .sort((a, b) => (b.createdAt?.toMillis?.() ?? 0) - (a.createdAt?.toMillis?.() ?? 0));
    if (!rooms.length) {
      list.innerHTML = '<li class="muted">لا توجد غرف حالياً</li>';
      return;
    }
    for (const r of rooms) {
      const li = document.createElement("li");
      const label = document.createElement("span");
      const mine = r.players?.X?.uid === me.uid;
      label.textContent = `${r.players?.X?.name ?? "؟"} — ${r.id}${mine ? " (غرفتك)" : ""}`;
      const btn = document.createElement("button");
      btn.className = "btn primary small";
      btn.textContent = mine ? "دخول" : "انضمام";
      btn.onclick = () => joinRoom(r.id);
      li.append(label, btn);
      list.append(li);
    }
  }, (e) => toast("خطأ في تحميل الغرف: " + e.message));
}

function stopLobby() {
  if (lobbyUnsub) { lobbyUnsub(); lobbyUnsub = null; }
}

function randomCode() {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let s = "";
  for (let i = 0; i < 6; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

$("createRoomBtn").onclick = async () => {
  $("createRoomBtn").disabled = true;
  try {
    let code;
    for (let attempt = 0; attempt < 5; attempt++) {
      const candidate = randomCode();
      const ref = doc(db, "rooms", candidate);
      const created = await runTransaction(db, async (tx) => {
        if ((await tx.get(ref)).exists()) return false;
        tx.set(ref, {
          status: "waiting",
          board: Array(9).fill(""),
          turn: "X",
          starter: "X",
          winner: null,
          winLine: [],
          players: { X: me },
          playerUids: [me.uid],
          scores: { X: 0, O: 0, draw: 0 },
          rematch: { X: false, O: false },
          createdAt: serverTimestamp(),
        });
        return true;
      });
      if (created) { code = candidate; break; }
    }
    if (!code) throw new Error("حاول مرة أخرى");
    openRoom(code);
  } catch (e) {
    toast("تعذّر إنشاء الغرفة: " + e.message);
  } finally {
    $("createRoomBtn").disabled = false;
  }
};

$("joinForm").onsubmit = (e) => {
  e.preventDefault();
  const code = $("joinCode").value.trim().toUpperCase();
  if (code.length !== 6) return toast("الرمز يتكون من 6 خانات");
  joinRoom(code);
};

async function joinRoom(code) {
  code = code.trim().toUpperCase();
  const ref = doc(db, "rooms", code);
  try {
    const result = await runTransaction(db, async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists()) return "missing";
      const r = snap.data();
      if (r.playerUids.includes(me.uid)) return "ok";
      if (r.status !== "waiting" || r.playerUids.length >= 2) return "full";
      tx.update(ref, {
        "players.O": me,
        playerUids: arrayUnion(me.uid),
        status: "playing",
      });
      return "ok";
    });
    if (result === "missing") { toast("الغرفة غير موجودة"); enterLobby(); return; }
    if (result === "full") { toast("الغرفة ممتلئة أو انتهت"); enterLobby(); return; }
    openRoom(code);
  } catch (e) {
    toast("تعذّر الانضمام: " + e.message);
    enterLobby();
  }
}

function setUrlRoom(code) {
  const url = new URL(location.href);
  if (code) url.searchParams.set("room", code);
  else url.searchParams.delete("room");
  history.replaceState(null, "", url);
}

/* ---------------- الغرفة واللعب ---------------- */

const boardEl = $("board");
const cells = [];
for (let i = 0; i < 9; i++) {
  const b = document.createElement("button");
  b.className = "cell";
  b.onclick = () => makeMove(i);
  boardEl.append(b);
  cells.push(b);
}

function myMark() {
  if (!room) return null;
  if (room.players.X?.uid === me.uid) return "X";
  if (room.players.O?.uid === me.uid) return "O";
  return null;
}

function openRoom(code) {
  stopLobby();
  closeRoomListeners();
  roomId = code;
  room = null;
  setUrlRoom(code);
  $("roomCode").textContent = code;
  $("messages").innerHTML = "";
  show("roomScreen");

  const ref = doc(db, "rooms", code);
  roomUnsubs.push(onSnapshot(ref, (snap) => {
    if (!snap.exists()) {
      toast("أُغلقت الغرفة");
      exitToLobby();
      return;
    }
    room = snap.data();
    renderRoom();
  }, (e) => { toast("خطأ: " + e.message); exitToLobby(); }));

  const mq = query(collection(db, "rooms", code, "messages"), orderBy("createdAt"), limitToLast(100));
  roomUnsubs.push(onSnapshot(mq, (snap) => {
    snap.docChanges().forEach((ch) => {
      if (ch.type === "added") renderMessage(ch.doc.data({ serverTimestamps: "estimate" }));
    });
  }));

  watchVoice();
}

function renderRoom() {
  const mark = myMark();
  for (const m of ["X", "O"]) {
    const p = room.players[m];
    const el = $("player" + m);
    el.querySelector(".pname").textContent = p ? p.name + (p.uid === me.uid ? " (أنت)" : "") : "بانتظار لاعب…";
    const img = el.querySelector("img");
    if (p?.photo) img.src = p.photo; else img.removeAttribute("src");
    el.classList.toggle("active", room.status === "playing" && room.turn === m);
    $("score" + m).textContent = room.scores[m];
  }
  $("scoreDraw").textContent = room.scores.draw;

  const winSet = new Set(room.winLine || []);
  room.board.forEach((v, i) => {
    cells[i].textContent = v;
    cells[i].className = "cell" + (v ? " " + v.toLowerCase() : "") + (winSet.has(i) ? " win" : "");
    cells[i].disabled = !!v || room.status !== "playing" || room.turn !== mark;
  });

  let status = "";
  if (room.status === "waiting") status = "بانتظار انضمام لاعب ثانٍ… أرسل له الرمز أو الرابط";
  else if (room.status === "playing") status = room.turn === mark ? "دورك! 🎯" : `دور ${room.players[room.turn]?.name ?? ""}`;
  else if (room.status === "finished") {
    if (room.winner === "draw") status = "تعادل 🤝";
    else status = room.winner === mark ? "فزت! 🎉" : `فاز ${room.players[room.winner].name}`;
  } else if (room.status === "abandoned") status = `غادر ${room.leftBy ?? "اللاعب الآخر"} الغرفة`;
  $("status").textContent = status;

  const rb = $("rematchBtn");
  rb.classList.toggle("hidden", room.status !== "finished");
  if (room.status === "finished") {
    const other = mark === "X" ? "O" : "X";
    rb.disabled = !!room.rematch?.[mark];
    rb.textContent = room.rematch?.[mark]
      ? "بانتظار موافقة الخصم…"
      : room.rematch?.[other] ? "الخصم يريد جولة جديدة — موافق" : "جولة جديدة";
  }

  const canCall = room.playerUids.length === 2 && room.status !== "abandoned";
  $("callBtn").disabled = !canCall;
}

async function makeMove(i) {
  const ref = doc(db, "rooms", roomId);
  try {
    await runTransaction(db, async (tx) => {
      const snap = await tx.get(ref);
      const r = snap.data();
      const mark = r.players.X.uid === me.uid ? "X" : r.players.O?.uid === me.uid ? "O" : null;
      if (!mark || r.status !== "playing" || r.turn !== mark || r.board[i]) return;
      const board = [...r.board];
      board[i] = mark;
      const result = checkWinner(board);
      const update = { board, turn: mark === "X" ? "O" : "X" };
      if (result) {
        update.status = "finished";
        update.winner = result.winner;
        update.winLine = result.line;
        update[`scores.${result.winner}`] = (r.scores[result.winner] || 0) + 1;
        update.rematch = { X: false, O: false };
      }
      tx.update(ref, update);
    });
  } catch (e) {
    toast("تعذّرت الحركة: " + e.message);
  }
}

$("rematchBtn").onclick = async () => {
  const ref = doc(db, "rooms", roomId);
  try {
    await runTransaction(db, async (tx) => {
      const r = (await tx.get(ref)).data();
      if (r.status !== "finished") return;
      const mark = r.players.X.uid === me.uid ? "X" : "O";
      const rematch = { ...r.rematch, [mark]: true };
      if (rematch.X && rematch.O) {
        const starter = r.starter === "X" ? "O" : "X"; // تبادل من يبدأ كل جولة
        tx.update(ref, {
          board: Array(9).fill(""),
          status: "playing",
          winner: null,
          winLine: [],
          starter,
          turn: starter,
          rematch: { X: false, O: false },
        });
      } else {
        tx.update(ref, { rematch });
      }
    });
  } catch (e) {
    toast("خطأ: " + e.message);
  }
};

$("copyLinkBtn").onclick = async () => {
  const url = new URL(location.href);
  url.searchParams.set("room", roomId);
  try {
    await navigator.clipboard.writeText(url.toString());
    toast("تم نسخ الرابط");
  } catch {
    prompt("انسخ الرابط:", url.toString());
  }
};

$("leaveRoomBtn").onclick = async () => {
  await leaveRoom();
  enterLobby();
};

async function leaveRoom() {
  if (!roomId) return;
  const id = roomId;
  const r = room;
  await hangup(true);
  closeRoomListeners();
  roomId = null;
  room = null;
  try {
    if (r?.status === "waiting" && r.players.X.uid === me.uid) {
      await deleteDoc(doc(db, "rooms", id));
    } else if (r && (r.status === "playing" || r.status === "finished")) {
      await updateDoc(doc(db, "rooms", id), { status: "abandoned", leftBy: me.name });
    }
  } catch (e) {
    console.warn(e);
  }
}

function exitToLobby() {
  hangup(false);
  closeRoomListeners();
  roomId = null;
  room = null;
  enterLobby();
}

function closeRoomListeners() {
  roomUnsubs.forEach((u) => u());
  roomUnsubs = [];
}

/* ---------------- الدردشة النصية ---------------- */

function renderMessage(m) {
  const li = document.createElement("li");
  if (m.uid === me.uid) li.classList.add("mine");
  const who = document.createElement("span");
  who.className = "who";
  const time = m.createdAt?.toDate?.().toLocaleTimeString("ar", { hour: "2-digit", minute: "2-digit" }) ?? "";
  who.textContent = `${m.name} · ${time}`;
  const text = document.createElement("span");
  text.textContent = m.text;
  li.append(who, text);
  const list = $("messages");
  list.append(li);
  list.scrollTop = list.scrollHeight;
}

$("chatForm").onsubmit = async (e) => {
  e.preventDefault();
  const input = $("chatInput");
  const text = input.value.trim();
  if (!text || !roomId) return;
  input.value = "";
  try {
    await addDoc(collection(db, "rooms", roomId, "messages"), {
      uid: me.uid,
      name: me.name,
      text: text.slice(0, 500),
      createdAt: serverTimestamp(),
    });
  } catch (err) {
    toast("تعذّر الإرسال: " + err.message);
    input.value = text;
  }
};

/* ---------------- المكالمة الصوتية (WebRTC) ----------------
   الإشارات (offer / answer / ICE candidates) تمر عبر Firestore:
   rooms/{id}/voice/call          ← العرض والرد
   rooms/{id}/voiceCandidates/*   ← مرشحات ICE لكل جلسة
*/

let pc = null;
let localStream = null;
let session = null;
let candUnsub = null;
let pendingCandidates = [];

const voiceRef = () => doc(db, "rooms", roomId, "voice", "call");
const candCol = () => collection(db, "rooms", roomId, "voiceCandidates");

function setVoiceUI(state, extra = "") {
  const labels = {
    idle: "غير متصل",
    calling: "جارٍ الاتصال… بانتظار الرد",
    incoming: `📞 ${extra} يتصل بك`,
    connecting: "جارٍ الاتصال…",
    connected: "🟢 متصل",
  };
  $("voiceStatus").textContent = labels[state];
  $("callBtn").classList.toggle("hidden", state !== "idle");
  $("answerBtn").classList.toggle("hidden", state !== "incoming");
  $("muteBtn").classList.toggle("hidden", !["calling", "connecting", "connected"].includes(state));
  $("hangupBtn").classList.toggle("hidden", state === "idle");
}

function watchVoice() {
  setVoiceUI("idle");
  roomUnsubs.push(onSnapshot(voiceRef(), async (snap) => {
    const v = snap.data();
    if (!v) {
      if (pc) { await hangup(false); toast("انتهت المكالمة"); }
      setVoiceUI("idle");
      return;
    }
    if (pc) {
      // نحن المتصل وجاء الرد
      if (v.session === session && v.answer && !pc.currentRemoteDescription) {
        await pc.setRemoteDescription(v.answer);
        flushCandidates();
        setVoiceUI("connecting");
      }
      // اتصل الطرفان في نفس اللحظة وتغلّب عرض الطرف الآخر: نرد على عرضه بدلاً من عرضنا
      if (v.session !== session && v.from !== me.uid && !v.answer) {
        await hangup(false);
        setVoiceUI("incoming", v.fromName);
      }
      return;
    }
    if (v.from !== me.uid && !v.answer) setVoiceUI("incoming", v.fromName);
    else setVoiceUI("idle"); // جلسة قديمة متبقية
  }));
}

async function setupPeer() {
  localStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
  pc = new RTCPeerConnection({ iceServers });
  localStream.getTracks().forEach((t) => pc.addTrack(t, localStream));
  pc.ontrack = (e) => {
    $("remoteAudio").srcObject = e.streams[0];
    $("remoteAudio").play().catch(() => {});
  };
  const mySession = session;
  pc.onicecandidate = (e) => {
    if (e.candidate && roomId) {
      addDoc(candCol(), { session: mySession, from: me.uid, c: e.candidate.toJSON() }).catch(console.warn);
    }
  };
  pc.onconnectionstatechange = () => {
    if (!pc) return;
    if (pc.connectionState === "connected") setVoiceUI("connected");
    if (pc.connectionState === "failed") {
      toast("تعذّر الاتصال الصوتي — قد تحتاج شبكتك خادم TURN");
      hangup(true);
    }
  };
  candUnsub = onSnapshot(query(candCol(), where("session", "==", mySession)), (snap) => {
    snap.docChanges().forEach((ch) => {
      const d = ch.doc.data();
      if (ch.type === "added" && d.from !== me.uid) addCandidate(d.c);
    });
  });
}

function addCandidate(c) {
  if (pc?.remoteDescription) pc.addIceCandidate(new RTCIceCandidate(c)).catch(console.warn);
  else pendingCandidates.push(c);
}

function flushCandidates() {
  const list = pendingCandidates;
  pendingCandidates = [];
  list.forEach(addCandidate);
}

$("callBtn").onclick = async () => {
  try {
    session = crypto.randomUUID();
    setVoiceUI("calling");
    await setupPeer();
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    await setDoc(voiceRef(), {
      session,
      from: me.uid,
      fromName: me.name,
      offer: { type: offer.type, sdp: offer.sdp },
      answer: null,
      createdAt: serverTimestamp(),
    });
  } catch (e) {
    toast(micError(e));
    hangup(false);
  }
};

$("answerBtn").onclick = async () => {
  try {
    const v = (await getDoc(voiceRef())).data();
    if (!v || v.answer) { setVoiceUI("idle"); return; }
    session = v.session;
    setVoiceUI("connecting");
    await setupPeer();
    await pc.setRemoteDescription(v.offer);
    flushCandidates();
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    await updateDoc(voiceRef(), { answer: { type: answer.type, sdp: answer.sdp } });
  } catch (e) {
    toast(micError(e));
    hangup(false);
  }
};

$("muteBtn").onclick = () => {
  if (!localStream) return;
  const track = localStream.getAudioTracks()[0];
  track.enabled = !track.enabled;
  $("muteBtn").textContent = track.enabled ? "كتم الصوت" : "إلغاء الكتم";
};

$("hangupBtn").onclick = () => hangup(true);

function micError(e) {
  if (e.name === "NotAllowedError") return "يجب السماح للمتصفح باستخدام الميكروفون";
  if (e.name === "NotFoundError") return "لم يتم العثور على ميكروفون";
  return "خطأ في المكالمة: " + e.message;
}

async function hangup(notify) {
  const endedSession = session;
  if (candUnsub) { candUnsub(); candUnsub = null; }
  if (pc) { pc.onconnectionstatechange = null; pc.close(); pc = null; }
  if (localStream) { localStream.getTracks().forEach((t) => t.stop()); localStream = null; }
  $("remoteAudio").srcObject = null;
  $("muteBtn").textContent = "كتم الصوت";
  pendingCandidates = [];
  session = null;
  if (roomId) setVoiceUI("idle");

  if (notify && roomId) {
    try {
      const batch = writeBatch(db);
      batch.delete(voiceRef());
      if (endedSession) {
        const old = await getDocs(query(candCol(), where("session", "==", endedSession)));
        old.forEach((d) => batch.delete(d.ref));
      }
      await batch.commit();
    } catch (e) {
      console.warn(e);
    }
  }
}

window.addEventListener("beforeunload", () => {
  if (pc && roomId) deleteDoc(voiceRef());
});
