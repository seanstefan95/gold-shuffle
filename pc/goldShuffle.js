// NAME: Gold Shuffle
// AUTHOR: seanstefan95
// VERSION: 1.0
// DESCRIPTION: Adds a "gold" true-random shuffle to the shuffle button.
//   Click cycle: Off -> Gold (true random) -> Spotify shuffle -> Smart shuffle -> Off
//
// Gold shuffle numbers every track in the current playlist / album / Liked Songs,
// orders them with a Fisher-Yates shuffle (every ordering equally likely), and
// replaces the play queue with that order.

console.log("[Gold Shuffle] file loaded");
(function goldShuffle() {
	const S = window.Spicetify;
	if (!S?.Player?.data || !S?.Platform?.PlayerAPI || !S?.showNotification) {
		setTimeout(goldShuffle, 500);
		return;
	}

	const GOLD = "#e8b923";
	const STORE_KEY = "goldShuffle:mode";

	let smartByUs = false; // set when we switch smart shuffle on, in case Spotify's flag lags
	let goldOn = false;
	try {
		goldOn = localStorage.getItem(STORE_KEY) === "gold";
	} catch (_) {}

	let goldContext = null; // context URI we last gold-shuffled
	let busy = false;

	// ---------- styling ----------
	const style = document.createElement("style");
	style.textContent = `
		.gold-shuffle-active,
		.gold-shuffle-active svg { color: ${GOLD} !important; fill: ${GOLD} !important; }
		.gold-shuffle-active::after { background-color: ${GOLD} !important; }
	`;
	document.head.appendChild(style);

	// ---------- shuffle algorithm ----------
	function fisherYates(items) {
		const a = items.slice();
		for (let i = a.length - 1; i > 0; i--) {
			const buf = new Uint32Array(1);
			crypto.getRandomValues(buf);
			const j = buf[0] % (i + 1);
			[a[i], a[j]] = [a[j], a[i]];
		}
		return a;
	}

	// ---------- track fetching ----------
	async function fetchPlaylistTracks(uri) {
		const res = await S.Platform.PlaylistAPI.getContents(uri, { limit: 9999999 });
		return res.items.filter((t) => t.isPlayable).map((t) => t.uri);
	}

	async function fetchLikedTracks() {
		const res = await S.Platform.LibraryAPI.getTracks({ limit: 9999999 });
		return res.items.filter((t) => t.isPlayable).map((t) => t.uri);
	}

	async function fetchAlbumTracks(uri) {
		const { data, errors } = await S.GraphQL.Request(S.GraphQL.Definitions.queryAlbumTracks, { uri, offset: 0, limit: 500 });
		if (errors) throw errors[0].message;
		return (data.albumUnion?.tracksV2 ?? data.albumUnion?.tracks ?? { items: [] }).items
			.filter(({ track }) => track.playability?.playable !== false)
			.map(({ track }) => track.uri);
	}

	async function fetchContextTracks(contextUri) {
		const type = S.URI.fromString(contextUri).type;
		const T = S.URI.Type;
		if (type === T.PLAYLIST || type === T.PLAYLIST_V2) return fetchPlaylistTracks(contextUri);
		if (type === T.ALBUM) return fetchAlbumTracks(contextUri);
		if (type === T.COLLECTION || contextUri.endsWith(":collection")) return fetchLikedTracks();
		throw "Gold shuffle works on playlists, albums and Liked Songs";
	}

	// ---------- queue replacement (same low-level hook Shuffle+ uses) ----------
	// Songs someone queued by hand ("Add to queue") are kept at the front, untouched.
	// Every song Gold Shuffle put in the queue. Spotify can relabel these as "queued" when you
	// switch playlists, so this list is what tells ours apart from songs a person queued.
	let goldAdded = new Set();

	function userQueued() {
		const next = S.Platform.PlayerAPI._queue?._queue?.nextTracks || [];
		return next.filter((t) => {
			const uri = t.contextTrack?.uri;
			if (!uri || uri === "spotify:delimiter") return false;
			if (t.contextTrack?.metadata?.gold_shuffle === "true") return false;
			return t.provider === "queue" && !goldAdded.has(uri);
		});
	}

	function goldItem(uri) {
		return { contextTrack: { uri, uid: "", metadata: { is_queued: "false", gold_shuffle: "true" } }, removed: [], blocked: [], provider: "context" };
	}

	function setQueue(uris, contextUri) {
		const keep = userQueued();
		const keepUris = new Set(keep.map((t) => t.contextTrack.uri));
		// A song someone already queued shouldn't also come up later in the gold order
		const gold = uris.filter((u) => !keepUris.has(u));
		goldAdded = new Set(gold); // replaces the old gold list, so its leftovers are dropped
		const { _queue, _client } = S.Platform.PlayerAPI._queue;
		const { prevTracks, queueRevision } = _queue;
		_client.setQueue({
			nextTracks: keep.concat(gold.concat("spotify:delimiter").map(goldItem)),
			prevTracks,
			queueRevision,
		});
		if (contextUri) {
			const { sessionId } = S.Platform.PlayerAPI.getState();
			S.Platform.PlayerAPI.updateContext(sessionId, { uri: contextUri, url: `context://${contextUri}` });
		}
		// No skip: the current song keeps playing, the shuffled list plays after it
	}

	// When a hand-queued song plays, drop its later copy from the gold order so it doesn't play twice
	function dropLaterDuplicate() {
		const cur = S.Player.data?.item?.uri;
		const q = S.Platform.PlayerAPI._queue?._queue;
		if (!cur || !q?.nextTracks) return;
		const i = q.nextTracks.findIndex((t) => t.provider === "context" && t.contextTrack?.uri === cur);
		if (i === -1) return;
		const next = q.nextTracks.filter((_, k) => k !== i);
		S.Platform.PlayerAPI._queue._client.setQueue({ nextTracks: next, prevTracks: q.prevTracks, queueRevision: q.queueRevision });
	}

	async function applyGold() {
		const contextUri = S.Player.data?.context?.uri;
		if (!contextUri || busy) return;
		busy = true;
		try {
			const tracks = await fetchContextTracks(contextUri);
			if (!tracks.length) throw "No playable tracks found";
			// Leave the song that's playing now out of the pool so it doesn't come right back
			const current = S.Player.data?.item?.uri;
			const idx = tracks.indexOf(current);
			if (idx !== -1) tracks.splice(idx, 1);
			const numbered = tracks.map((uri, i) => ({ n: i + 1, uri })); // number every song
			const order = fisherYates(numbered); // randomize the numbers
			setQueue(order.map((t) => t.uri), contextUri);
			goldContext = contextUri;
			S.showNotification(`Gold shuffle: ${order.length} songs queued after this one`);
		} catch (e) {
			console.error("[Gold Shuffle]", e);
			S.showNotification(typeof e === "string" ? e : "Gold shuffle failed, see console");
		} finally {
			busy = false;
		}
	}

	// ---------- buttons ----------
	// Two shuffle buttons are handled: the one in the bottom playbar, and the one
	// in a playlist / album / Liked Songs page header (next to the big play button).
	// Spotify renames classes between versions, so match several ways.
	const SHUFFLE_SEL = "[data-testid='control-button-shuffle'], .main-shuffleButton-button, button[aria-label*='huffle' i]";
	const PLAYBAR_SEL = ".main-nowPlayingBar-container, [data-testid='now-playing-bar'], footer, .player-controls";
	const HEADER_SEL = ".main-actionBar-ActionBar, [data-testid='action-bar-row'], [data-testid='action-bar'], .main-actionBar-ActionBarRow";

	function classify(btn) {
		if (!btn) return null;
		if (btn.closest(PLAYBAR_SEL)) return "playbar";
		if (btn.closest(HEADER_SEL)) return "header";
		return null;
	}
	function allButtons() {
		return [...document.querySelectorAll(SHUFFLE_SEL)].filter((b) => classify(b));
	}
	function getButton() {
		return allButtons().find((b) => classify(b) === "playbar");
	}

	// Context URI of the page you're looking at, e.g. spotify:playlist:abc
	function pageContextUri() {
		const path = S.Platform.History?.location?.pathname || location.pathname;
		let m;
		if ((m = path.match(/^\/(playlist|album)\/([A-Za-z0-9]+)/))) return `spotify:${m[1]}:${m[2]}`;
		if (path.startsWith("/collection/tracks")) {
			const user = S.Platform.username || S.Platform.UserAPI?._product_state?.cache?.get?.("username");
			return user ? `spotify:user:${user}:collection` : null;
		}
		return null;
	}

	function paint() {
		for (const btn of allButtons()) {
			btn.classList.toggle("gold-shuffle-active", currentMode() === "gold");
		}
	}

	// Spotify's ShuffleAPI (confirmed from your console output):
	//   setShuffle(contextUri, mode)   modes: 0 = off, 1 = shuffle, 2 = smart shuffle
	//   getShuffle(contextUri)         returns the current mode number
	const SHUFFLE_OFF = 0, SHUFFLE_ON = 1, SHUFFLE_SMART = 2;
	async function setSpotifyShuffle(mode) {
		const ctx = S.Player.data?.context?.uri;
		if (!ctx) return false;
		try {
			await Promise.race([S.Platform.ShuffleAPI.setShuffle(ctx, mode), new Promise((r) => setTimeout(r, 2000))]);
			return true;
		} catch (e) {
			console.warn("[Gold Shuffle] ShuffleAPI.setShuffle failed", mode, e);
			return false;
		}
	}
	async function enableSmart() {
		if (await setSpotifyShuffle(SHUFFLE_SMART)) {
			smartByUs = true;
			return true;
		}
		S.Player.setShuffle(false);
		S.showNotification("Smart shuffle unavailable here, shuffle off");
		return false;
	}

	// Spotify's own button already cycles: off -> shuffle -> smart shuffle -> off.
	// We only add one step in front of it: off -> GOLD. Every other click is
	// passed straight through to Spotify, so its own icons and behavior are used.
	function currentMode() {
		const d = S.Player.data || {};
		if (d.smartShuffle || (smartByUs && d.shuffle)) return "smart";
		if (d.shuffle) return "shuffle";
		return goldOn ? "gold" : "off";
	}
	function saveGold() {
		try {
			localStorage.setItem(STORE_KEY, goldOn ? "gold" : "off");
		} catch (_) {}
	}

	document.addEventListener(
		"click",
		(ev) => {
			const btn = ev.target.closest?.(SHUFFLE_SEL);
			const where = classify(btn);
			if (!where) return;
			const m = currentMode();
			if (m === "off") {
				// Off -> Gold: handled by us, Spotify never sees this click
				ev.preventDefault();
				ev.stopImmediatePropagation();
				goldOn = true;
				saveGold();
				paint();
				// Header button on a page that isn't playing: just arm gold;
				// it shuffles when you press play there.
				const playingHere = where === "playbar" || pageContextUri() === S.Player.data?.context?.uri;
				if (playingHere) applyGold();
				else S.showNotification("Gold shuffle on, press play");
			} else if (m === "gold") {
				// Gold -> Spotify shuffle: let Spotify handle the click
				goldOn = false;
				goldContext = null;
				saveGold();
				setTimeout(paint, 50);
			} else if (m === "smart" && smartByUs) {
				// Smart -> Off: switch it off through the same API we turned it on with
				ev.preventDefault();
				ev.stopImmediatePropagation();
				smartByUs = false;
				setSpotifyShuffle(SHUFFLE_OFF).then((ok) => ok || S.Player.setShuffle(false));
			} else if (m === "shuffle") {
				// Shuffle -> Smart: done through Spotify's ShuffleAPI
				ev.preventDefault();
				ev.stopImmediatePropagation();
				enableSmart();
			}
			// smart -> off: Spotify handles this itself

			// Report the real state Spotify ends up in, so we can see the cycle
			setTimeout(() => {
				const d = S.Player.data || {};
				const names = { gold: "Gold shuffle", shuffle: "Spotify shuffle", smart: "Smart shuffle", off: "Shuffle off" };
				console.log("[Gold Shuffle] after click:", { shuffle: d.shuffle, smartShuffle: d.smartShuffle, gold: goldOn });
				S.showNotification(names[currentMode()]);
			}, 600);
		},
		true
	);

	// If shuffle is turned on anywhere else (e.g. your phone), gold turns off
	S.Player.addEventListener("onplaypause", () => {
		const d = S.Player.data || {};
		if (goldOn && (d.shuffle || d.smartShuffle)) {
			goldOn = false;
			saveGold();
			paint();
		}
	});

	// Re-shuffle when you start a different playlist/album while in gold mode
	S.Player.addEventListener("songchange", () => {
		const ctx = S.Player.data?.context?.uri;
		if (goldOn && !S.Player.data?.shuffle && ctx && ctx !== goldContext) applyGold();
		else if (goldOn && goldContext) try { dropLaterDuplicate(); } catch (e) { console.warn("[Gold Shuffle] dedupe", e); }
		setTimeout(paint, 200);
	});

	// Spotify re-renders the playbar; keep our color on it
	new MutationObserver(paint).observe(document.body, { childList: true, subtree: true });

	if (goldOn && (S.Player.data?.shuffle || S.Player.data?.smartShuffle)) goldOn = false;
	paint();
	console.log("[Gold Shuffle] ready, mode:", currentMode(), "button found:", !!getButton());
	S.showNotification("Gold Shuffle ready");
})();
