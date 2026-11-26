package main

// App sign-in. The app opens /auth/app in the phone's browser with a PKCE challenge. Once the
// owner is signed in there and taps Allow, the browser hands a one-time code to the app
// (buzzlab://signin), and the app swaps code + verifier for a key of its own. Each phone has its
// own key, sent as a Bearer token, so one phone can be signed out without touching the others.

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"html"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

const deviceSchema = `CREATE TABLE IF NOT EXISTS devices(id INTEGER PRIMARY KEY,token TEXT NOT NULL UNIQUE,email TEXT NOT NULL,name TEXT NOT NULL,created INTEGER NOT NULL,used INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS appcodes(code TEXT PRIMARY KEY,challenge TEXT NOT NULL,email TEXT NOT NULL,name TEXT NOT NULL,expires INTEGER NOT NULL);`

var challengeRe = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)

// allowed is false once an address leaves the allowlist, which ends its sessions and app keys too.
func (s *server) allowed(email string) bool { return s.google != nil && s.google.emails[email] }

// deviceEmail returns who an app key belongs to.
func (s *server) deviceEmail(r *http.Request) (string, bool) {
	tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || len(tok) != 43 {
		return "", false
	}
	var e string
	if s.db.QueryRow(`SELECT email FROM devices WHERE token=?`, hashToken(tok)).Scan(&e) != nil || !s.allowed(e) {
		return "", false
	}
	s.db.Exec(`UPDATE devices SET used=? WHERE token=?`, time.Now().UnixMilli(), hashToken(tok))
	return e, true
}

func deviceName(n string) string {
	n = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, strings.TrimSpace(n))
	if n == "" {
		return "A phone"
	}
	if r := []rune(n); len(r) > 60 {
		n = string(r[:60])
	}
	return n
}

const appPage = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>Buzz Lab app sign-in</title>
<style>:root{color-scheme:dark;--bg:#021b29;--card:#043349;--fg:#f2f0ea;--mu:#9db6c3;--ac:#ff803a;--ln:#14506c;--deep:#01131d}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif;padding:16px}main{width:100%;max-width:400px;background:var(--card);border:1px solid var(--ln);border-radius:12px;padding:28px 24px;display:grid;gap:16px}h1{margin:0;font-size:24px}p{margin:0;color:var(--mu)}b{color:var(--fg)}form{display:grid;gap:10px}button,a.c{font:600 16px system-ui,sans-serif;min-height:48px;border-radius:8px;border:0;background:var(--ac);color:var(--deep);cursor:pointer;display:grid;place-items:center;text-decoration:none}a.c{background:transparent;color:var(--fg);border:1px solid var(--ln);font-weight:500}</style></head><body><main>
<h1>Sign in the app?</h1><p>The Buzz Lab app on <b>{{NAME}}</b> will upload drives and make share links as <b>{{EMAIL}}</b>. You can sign it out in the app's Settings.</p>
<form method="post" action="/auth/app"><input type="hidden" name="c" value="{{C}}"><input type="hidden" name="name" value="{{NAME}}"><button>Allow</button><a class="c" href="/">Cancel</a></form>
</main></body></html>`

func appHeaders(w http.ResponseWriter) {
	shareHeaders(w, "none")
	// the Allow form posts here, and the answer sends the browser on to the app
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' buzzlab:; base-uri 'none'; frame-ancestors 'none'")
	// with no-referrer, Chrome sends "Origin: null" on the Allow form and the origin check below refuses it
	w.Header().Set("Referrer-Policy", "same-origin")
}

// appAsk asks the signed-in owner to confirm, so a page elsewhere can't sign an app in unseen.
func (s *server) appAsk(w http.ResponseWriter, r *http.Request) {
	email, ok := s.sessionEmail(r)
	if !ok {
		http.Redirect(w, r, "/login?next="+url.QueryEscape(r.URL.RequestURI()), http.StatusFound)
		return
	}
	c := r.URL.Query().Get("c")
	if !challengeRe.MatchString(c) {
		http.Error(w, "This sign-in link is broken. Start again from the app.", 400)
		return
	}
	appHeaders(w)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	io.WriteString(w, strings.NewReplacer("{{NAME}}", html.EscapeString(deviceName(r.URL.Query().Get("name"))), "{{EMAIL}}", html.EscapeString(email), "{{C}}", c).Replace(appPage))
}

func (s *server) appAllow(w http.ResponseWriter, r *http.Request) {
	email, ok := s.sessionEmail(r)
	if !ok {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}
	if o := r.Header.Get("Origin"); o != "" && (s.google == nil || o != s.google.base) {
		http.Error(w, "wrong origin", 403)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	c := r.PostFormValue("c")
	if !challengeRe.MatchString(c) {
		http.Error(w, "This sign-in link is broken. Start again from the app.", 400)
		return
	}
	code, now := random(32), time.Now()
	s.db.Exec(`DELETE FROM appcodes WHERE expires<?`, now.UnixMilli())
	if _, err := s.db.Exec(`INSERT INTO appcodes VALUES(?,?,?,?,?)`, hashToken(code), c, email, deviceName(r.PostFormValue("name")), now.Add(5*time.Minute).UnixMilli()); err != nil {
		http.Error(w, "database", 500)
		return
	}
	appHeaders(w)
	http.Redirect(w, r, "buzzlab://signin?code="+code, http.StatusSeeOther)
}

// appToken swaps a one-time code and its PKCE verifier for the phone's own key.
func (s *server) appToken(w http.ResponseWriter, r *http.Request) {
	ip := "app:" + clientIP(r)
	if s.blocked(ip) {
		fail(w, http.StatusTooManyRequests, "too many tries, wait 10 minutes")
		return
	}
	var q struct {
		Code     string `json:"code"`
		Verifier string `json:"verifier"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&q) != nil || len(q.Code) != 43 || len(q.Verifier) < 43 || len(q.Verifier) > 128 {
		s.note(ip)
		fail(w, 400, "needs code and verifier")
		return
	}
	var c, email, name string
	var exp int64
	err := s.db.QueryRow(`DELETE FROM appcodes WHERE code=? RETURNING challenge,email,name,expires`, hashToken(q.Code)).Scan(&c, &email, &name, &exp)
	h := sha256.Sum256([]byte(q.Verifier))
	if err != nil || exp < time.Now().UnixMilli() || subtle.ConstantTimeCompare([]byte(base64.RawURLEncoding.EncodeToString(h[:])), []byte(c)) != 1 || !s.allowed(email) {
		s.note(ip)
		fail(w, 400, "this sign-in has expired or was already used, start again")
		return
	}
	tok, now := random(32), time.Now().UnixMilli()
	if _, err := s.db.Exec(`INSERT INTO devices(token,email,name,created,used) VALUES(?,?,?,?,?)`, hashToken(tok), email, name, now, now); err != nil {
		fail(w, 500, "database")
		return
	}
	writeJSON(w, 200, map[string]string{"token": tok, "email": email})
}

// appSignOut ends the key that sent it.
func (s *server) appSignOut(w http.ResponseWriter, r *http.Request) {
	if tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "); ok {
		s.db.Exec(`DELETE FROM devices WHERE token=?`, hashToken(tok))
	}
	w.WriteHeader(204)
}
