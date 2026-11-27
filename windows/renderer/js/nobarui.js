'use strict';
/* UI Nobar: menu masuk, lobi, kotak chat, daftar anggota, reaksi. */
var EMOJIS = ['😂', '❤️', '😮', '👏', '🔥', '😢'];

function memberStrip(room, canKick) {
  var box = h('div', { class: 'members' });
  var now = room.serverNow();
  room.members.forEach(function (m) {
    var st = room.peerState[m.uid];
    var isHost = room.info && room.info.host === m.uid;
    var status = m.buf ? 'menunggu' : 'siap';
    var el = h('div', { class: 'mem' + (room.speaking && room.speaking[m.uid] ? ' speaking' : '') }, avatar(m.name, 28),
      h('div', {}, h('div', {}, m.name + (m.uid === room.me ? ' (kamu)' : '') + (isHost ? ' ★' : '')),
        h('div', { class: 'st ' + (m.buf ? 'wait' : 'ok') }, status + (m.voice ? (m.mic ? ' · 🎙' : ' · 🔇') : '') + (st && m.voice && m.uid !== room.me ? ' · ' + st : ''))));
    if (m.uid !== room.me && m.voice && room.voice) {
      var vr = h('input', { type: 'range', min: 0, max: 100, class: 'vol pv', title: 'Volume suara ' + m.name, value: Math.round(room.voice.getVol(m.uid) * 100) });
      vr.addEventListener('input', function () { room.voice.setVol(m.uid, vr.value / 100); });
      el.append(vr);
    }
    if (canKick && room.isHost && m.uid !== room.me) el.append(h('button', { title: 'Keluarkan', onclick: function () {
      confirmBox('Keluarkan ' + m.name + '?', 'Dia tidak bisa masuk lagi ke ruangan ini.', 'KELUARKAN', function () { room.kick(m.uid); });
    } }, icon('close', 14)));
    box.append(el);
  });
  return box;
}

function reactionBar(room) {
  return h('div', { class: 'reacts' }, EMOJIS.map(function (e) { return h('button', { onclick: function () { room.react(e); } }, e); }));
}

/** Kotak chat: daftar pesan + isian. Mengembalikan elemen yang bisa menyegarkan diri. */
function chatBox(room, fontSize) {
  var list = h('div', { class: 'chatlist', style: { fontSize: (fontSize || 16) + 'px' } });
  var inp = h('input', { placeholder: 'Tulis pesan…', maxlength: 300 });
  var replyTo = null;
  var bar = h('div', { class: 'replybar', style: { display: 'none' } });
  var setReply = function (m) {
    replyTo = m; clear(bar);
    bar.style.display = m ? 'flex' : 'none';
    if (m) bar.append(h('div', { class: 'grow' }, h('b', {}, 'Membalas ' + m.name), h('div', { class: 'q' }, m.text)),
      h('button', { class: 'iconbtn', title: 'Batal membalas', onclick: function () { setReply(null); } }, icon('close', 14)));
    inp.focus();
  };
  var send = function () { var v = inp.value; if (!v.trim()) return; room.send(v, replyTo); inp.value = ''; setReply(null); /* fokus dipertahankan */ };
  inp.addEventListener('keydown', function (e) { e.stopPropagation(); if (e.key === 'Enter') send(); });
  var box = h('div', { class: 'chatbox' }, list, bar, h('div', { class: 'chatin' }, inp, h('button', { class: 'iconbtn', onclick: function () { send(); inp.focus(); } }, icon('send', 18))));
  var lastKey = '';
  var toBottom = function () { list.scrollTop = list.scrollHeight; };
  var draw = function () {
    var key = room.chat.length + ':' + (room.chat.length ? room.chat[room.chat.length - 1].id : '');
    if (key === lastKey) return; lastKey = key;
    clear(list);
    if (!room.chat.length) list.append(h('div', { style: { color: 'var(--subtle)' } }, 'Belum ada pesan. Sapa temanmu! Arahkan kursor ke pesan lalu klik ↩ untuk membalas.'));
    room.chat.forEach(function (m) {
      var mine = m.uid === room.me;
      var b = h('div', { class: 'bub ' + (mine ? 'me' : 'o') });
      if (!mine) b.append(h('b', {}, m.name));
      if (m.q) b.append(h('div', { class: 'quote' }, h('b', {}, m.qn), h('div', {}, m.qm)));
      b.append(h('div', { class: 'tx' }, m.text), h('button', { class: 'rep', title: 'Balas', onclick: function () { setReply(m); } }, '↩'));
      list.append(h('div', { class: 'brow ' + (mine ? 'me' : 'o') }, b));
    });
    toBottom(); requestAnimationFrame(toBottom); // selalu di pesan terbaru
  };
  draw();
  setTimeout(toBottom, 60); // saat kotak baru dipasang ke halaman
  box.refresh = draw; box.input = inp;
  return box;
}

/** Deretan video call (kamera sendiri + peserta yang menyalakan kamera). null bila tidak ada. Elemen video disimpan di room.vels agar tidak berkedip. */
function videoGrid(room) {
  var box = h('div', { class: 'vgrid' });
  var tile = function (uid, stream, name, mine) {
    var el = room.vels[uid];
    if (!el) {
      var v = h('video', { playsinline: true }); v.muted = true; v.autoplay = true; v.srcObject = stream;
      if (mine) v.style.transform = 'scaleX(-1)';
      el = h('div', { class: 'vtile' }, v, h('span', {}, name)); room.vels[uid] = el;
      v.play().catch(function () {});
    }
    box.append(el);
  };
  if (room.camOn && room.voice && room.voice.stream) tile(room.me, new MediaStream(room.voice.stream.getVideoTracks()), 'Kamu', true);
  room.members.forEach(function (m) { if (m.uid !== room.me && m.cam && room.videos[m.uid]) tile(m.uid, room.videos[m.uid], m.name, false); });
  return box.children.length ? box : null;
}

function micAction(room) {
  if (!room.voiceOn) room.enableVoice(); else room.toggleMic();
}

/* ---------- Layar Nobar ---------- */
function nobarScreen(state) {
  var page = h('div', { class: 'page' });
  var room = Nobar.room;
  var rer = state.rerender;
  page.append(h('div', { class: 'topbar' }, h('button', { class: 'iconbtn', onclick: function () { UI.back(); } }, icon('back', 20)), h('h1', { style: { margin: 0 } }, 'Nonton Bareng')));
  var film = state.film ? App.find(state.film) : null;

  if (!room) {
    var wrap = h('div', { class: 'nb-wrap', style: { marginTop: '16px' } });
    if (film) {
      wrap.append(h('button', { class: 'opt', onclick: async function () { if (await Nobar.create(film, function () { return App.nickname; })) rer(); else rer(); } },
        icon('plus', 26), h('div', {}, h('b', {}, 'Buat ruangan untuk "' + film.name + '"'),
          h('small', {}, 'Kamu jadi host. Ruangan tetap aktif sampai kamu menutupnya. Maks ' + MAX_MEMBERS + ' orang termasuk kamu.'))));
    }
    var hist = Nobar.history;
    if (hist.length) {
      var hp = h('div', { class: 'panel', style: { maxWidth: 'none' } }, h('h4', {}, 'Ruangan sebelumnya'));
      hist.forEach(function (x) {
        hp.append(h('div', { class: 'hist' }, h('div', { class: 'grow' }, h('b', {}, x.filmName || x.filmId), h('small', {}, x.code + (x.host ? ' · kamu host' : ''))),
          btn('LANJUTKAN', null, true, async function () { await Nobar.join(x.code, function () { return App.nickname; }); rer(); }),
          h('button', { class: 'iconbtn', title: 'Lupakan', onclick: function () { Nobar.forget(x.code); rer(); } }, icon('close', 16))));
      });
      wrap.append(hp);
    }
    var code = h('input', { class: 'field', placeholder: 'Kode ruangan (6 karakter)', maxlength: 40, style: { textTransform: 'uppercase', letterSpacing: '4px', fontWeight: '800', fontSize: '18px' } });
    var go = async function () { var m = /join\/([A-Za-z0-9]{6})/i.exec(code.value); await Nobar.join(m ? m[1] : code.value, function () { return App.nickname; }); rer(); };
    code.addEventListener('keydown', function (e) { if (e.key === 'Enter') go(); });
    wrap.append(h('div', { class: 'panel', style: { maxWidth: 'none' } }, h('h4', {}, 'Gabung dengan kode'), h('p', {}, 'Punya kode dari teman? Masukkan di sini.'), code,
      h('div', { style: { marginTop: '10px' } }, btn(Nobar.busy ? 'MENYAMBUNG…' : 'GABUNG', null, false, function () { if (!Nobar.busy) go(); }))));
    if (Nobar.error) wrap.append(h('div', { class: 'err' }, Nobar.error));
    if (!App.nickname) wrap.append(h('div', { class: 'gold' }, 'Tip: isi nama sapaan di Profil agar temanmu mengenalimu.'));
    page.append(wrap);
    return page;
  }

  /* ---- Lobi ---- */
  var info = room.info; var f = info ? App.find(info.filmId) : null;
  var lobby = h('div', { class: 'lobby' });
  var codeBox = h('div', { class: 'codebox' }, h('div', { class: 'grow' }, h('div', { class: 'code' }, room.code), h('div', { class: 'fn' }, info ? info.filmName : 'Memuat…')),
    h('button', { class: 'iconbtn', title: 'Salin kode', onclick: function () { navigator.clipboard.writeText(room.code).then(function () { toast('Kode disalin'); }); } }, icon('copy', 18)),
    h('button', { class: 'iconbtn', title: 'Bagikan undangan', onclick: function () {
      var t = 'Ayo nobar "' + (room.info ? room.info.filmName : '') + '" bareng di Aphodius!\nKode ruangan: ' + room.code + '\nBuka link: aphodius://join/' + room.code + '\n(atau Aphodius > Nobar > Gabung dengan kode)';
      navigator.clipboard.writeText(t).then(function () { toast('Undangan disalin. Tempel ke WhatsApp/Telegram', 3500); });
    } }, icon('send', 18)));
  lobby.append(codeBox);
  var offline = h('div', { class: 'gold', style: { fontSize: '12px' } }); lobby.append(offline);
  var memHost = h('div'); var vidHost = h('div'); var ctlRow = h('div', { class: 'acts' }); var errRow = h('div', { class: 'err' });
  lobby.append(h('div', { style: { color: 'var(--muted)', fontSize: '12px', fontWeight: 700 } }, ''), memHost, ctlRow, errRow, vidHost);
  var chat = chatBox(room, 16); lobby.append(chat);
  var warn = h('div', { class: 'gold', style: { fontSize: '12px' } }); lobby.append(warn);
  var foot = h('div'); lobby.append(foot);
  var confirmClose = false;
  var draw = function () {
    offline.textContent = room.status === 'offline' ? 'Koneksi terputus, mencoba menyambung lagi…' : '';
    codeBox.querySelector('.fn').textContent = room.info ? room.info.filmName : 'Memuat…';
    clear(memHost); memHost.append(h('div', { style: { color: 'var(--muted)', fontSize: '12px', fontWeight: 700, marginBottom: '6px' } }, room.members.length + '/' + MAX_MEMBERS + ' bergabung'), memberStrip(room, true));
    clear(ctlRow);
    ctlRow.append(chip(!room.voiceOn ? 'Nyalakan suara' : room.micOn ? 'Mik aktif' : 'Mik mati', room.voiceOn && room.micOn, function () { micAction(room); }));
    ctlRow.append(chip(room.camOn ? 'Kamera aktif' : 'Nyalakan kamera', room.camOn, function () { room.toggleCam(); }));
    if (room.voiceOn) ctlRow.append(chip('Matikan suara', false, function () { room.disableVoice(); }));
    if (room.isHost) ctlRow.append(chip(room.info && room.info.ctl === 'all' ? 'Semua boleh kendali: ya' : 'Semua boleh kendali: tidak', room.info && room.info.ctl === 'all', function () { room.setControl(!(room.info && room.info.ctl === 'all')); }));
    else ctlRow.append(h('span', { style: { color: 'var(--subtle)', fontSize: '12px', alignSelf: 'center' } }, room.info && room.info.ctl === 'all' ? 'Semua boleh mengendalikan' : 'Hanya host yang mengendalikan'));
    errRow.textContent = room.voiceError || '';
    clear(vidHost); var vg = videoGrid(room); if (vg) vidHost.append(vg);
    var cur = room.info ? App.find(room.info.filmId) : null;
    warn.textContent = room.info && !cur ? 'Film ini perlu fitur Adults/Extraordinary yang aktif di profilmu. Aktifkan dulu di Profil untuk ikut menonton; chat dan suara tetap jalan.' : '';
    clear(foot);
    if (confirmClose) {
      foot.append(h('div', { class: 'gold', style: { fontSize: '12px', marginBottom: '6px' } }, 'Tutup ruangan untuk semua orang? Ruangan tidak bisa dibuka lagi.'),
        h('div', { class: 'acts' }, btn('BATAL', null, false, function () { confirmClose = false; draw(); }), btn('YA, TUTUP', null, true, function () { Nobar.close(); rer(); })));
    } else {
      foot.append(h('div', { class: 'acts' }, btn('KELUAR', null, false, function () { Nobar.leave(); rer(); }),
        btn(room.isHost ? 'TONTON' : 'IKUT TONTON', 'play', !!cur, function () { if (cur) UI.watchRoom(cur); })));
      if (room.isHost) foot.append(h('a', { style: { color: 'var(--muted)', fontSize: '12px', cursor: 'pointer', display: 'inline-block', marginTop: '10px' }, onclick: function () { confirmClose = true; draw(); } }, 'Tutup ruangan'));
      else foot.append(h('div', { style: { color: 'var(--subtle)', fontSize: '12px', marginTop: '8px' } }, 'Keluar tidak menutup ruangan. Kamu bisa lanjut lagi dari Nobar.'));
    }
  };
  draw();
  state.disposers.push(room.on(function () { if (room.status === 'closed') { rer(); return; } draw(); chat.refresh(); }));
  page.append(lobby);
  return page;
}
