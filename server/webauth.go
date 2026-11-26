package main

// Browser sign-in with Google. The app keeps using basic auth; browsers get a session cookie.
// The server has no route out, so in production GOOGLE_TOKEN_URL points at a Caddy relay that
// forwards only to Google's token endpoint.

import (
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"
)

//go:embed login.html
var loginPage string

const (
	sessionCookie = "buzz_session"
	oauthCookie   = "buzz_oauth"
	sessionDays   = 30
	sessionSchema = `CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,email TEXT NOT NULL,created INTEGER NOT NULL,expires INTEGER NOT NULL);`
)

type googleAuth struct {
	id, secret, tokenURL, base string
	emails                     map[string]bool
	client                     *http.Client
}

func newGoogle(id, secret, tokenURL, base, emails string) *googleAuth {
	if id == "" || secret == "" || base == "" {
		return nil
	}
	g := &googleAuth{id: id, secret: secret, tokenURL: tokenURL, base: strings.TrimRight(base, "/"), emails: map[string]bool{}, client: &http.Client{Timeout: 10 * time.Second}}
	for _, e := range strings.Split(emails, ",") {
		if e = strings.ToLower(strings.TrimSpace(e)); e != "" {
			g.emails[e] = true
		}
	}
	return g
}
func (g *googleAuth) secure() bool { return strings.HasPrefix(g.base, "https://") }

func (s *server) setCookie(w http.ResponseWriter, name, val, path string, age int) {
	http.SetCookie(w, &http.Cookie{Name: name, Value: val, Path: path, MaxAge: age, HttpOnly: true, Secure: s.google != nil && s.google.secure(), SameSite: http.SameSiteLaxMode})
}

// sessionEmail returns who a valid session cookie belongs to.
func (s *server) sessionEmail(r *http.Request) (string, bool) {
	c, err := r.Cookie(sessionCookie)
	if err != nil || len(c.Value) != 43 {
		return "", false
	}
	var e string
	if s.db.QueryRow(`SELECT email FROM sessions WHERE token=? AND expires>?`, hashToken(c.Value), time.Now().UnixMilli()).Scan(&e) != nil || !s.allowed(e) {
		return "", false
	}
	return e, true
}

func (s *server) loginView(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.sessionEmail(r); ok {
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}
	msg := map[string]string{
		"denied":  "That Google account isn't allowed here.",
		"failed":  "Sign-in didn't work. Try again.",
		"expired": "Sign-in took too long. Try again.",
		"busy":    "Too many failed sign-ins. Wait 10 minutes.",
	}[r.URL.Query().Get("e")]
	if s.google == nil {
		msg = "Google sign-in isn't set up on this server."
	}
	shareHeaders(w, "none")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	href := "/auth/google"
	if n := safeNext(r.URL.Query().Get("next")); n != "/" {
		href += "?next=" + url.QueryEscape(n)
	}
	io.WriteString(w, strings.NewReplacer("{{MSG}}", msg, "{{HREF}}", href).Replace(loginPage))
}

// safeNext keeps a post-sign-in destination only when it is a path on this site.
func safeNext(p string) string {
	u, err := url.Parse(p)
	if err != nil || len(p) > 512 || !strings.HasPrefix(p, "/") || strings.HasPrefix(p, "//") || strings.ContainsAny(p, "\\\r\n") ||
		u.Scheme != "" || u.Host != "" || p == "/login" || (strings.HasPrefix(p, "/auth/") && u.Path != "/auth/app") {
		return "/"
	}
	return p
}

func (s *server) googleStart(w http.ResponseWriter, r *http.Request) {
	g := s.google
	if g == nil {
		http.Redirect(w, r, "/login", http.StatusFound)
		return
	}
	state, verifier, nonce := random(24), random(32), random(24)
	next := base64.RawURLEncoding.EncodeToString([]byte(safeNext(r.URL.Query().Get("next"))))
	s.setCookie(w, oauthCookie, state+"."+verifier+"."+nonce+"."+next, "/auth/", 600)
	ch := sha256.Sum256([]byte(verifier))
	q := url.Values{
		"client_id": {g.id}, "redirect_uri": {g.base + "/auth/callback"}, "response_type": {"code"},
		"scope": {"openid email"}, "state": {state}, "nonce": {nonce}, "prompt": {"select_account"},
		"code_challenge": {base64.RawURLEncoding.EncodeToString(ch[:])}, "code_challenge_method": {"S256"},
	}
	http.Redirect(w, r, "https://accounts.google.com/o/oauth2/v2/auth?"+q.Encode(), http.StatusFound)
}

type idClaims struct {
	Iss      string `json:"iss"`
	Aud      string `json:"aud"`
	Exp      int64  `json:"exp"`
	Nonce    string `json:"nonce"`
	Email    string `json:"email"`
	Verified bool   `json:"email_verified"`
}

// exchange swaps the code for an ID token. The token comes straight back from Google's token
// endpoint over the connection we opened, so its signature need not be checked (OpenID Connect 3.1.3.7).
func (g *googleAuth) exchange(code, verifier string) (idClaims, error) {
	var c idClaims
	res, err := g.client.PostForm(g.tokenURL, url.Values{
		"code": {code}, "client_id": {g.id}, "client_secret": {g.secret}, "redirect_uri": {g.base + "/auth/callback"},
		"grant_type": {"authorization_code"}, "code_verifier": {verifier},
	})
	if err != nil {
		return c, err
	}
	defer res.Body.Close()
	var t struct {
		IDToken string `json:"id_token"`
	}
	if res.StatusCode != 200 || json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&t) != nil {
		return c, errors.New("token endpoint said " + res.Status)
	}
	p := strings.Split(t.IDToken, ".")
	if len(p) != 3 {
		return c, errors.New("bad id token")
	}
	b, err := base64.RawURLEncoding.DecodeString(p[1])
	if err != nil || json.Unmarshal(b, &c) != nil {
		return c, errors.New("bad id token")
	}
	return c, nil
}

func (s *server) googleCallback(w http.ResponseWriter, r *http.Request) {
	g, ip := s.google, "login:"+clientIP(r)
	back := func(e string) { http.Redirect(w, r, "/login?e="+e, http.StatusFound) }
	if g == nil {
		back("failed")
		return
	}
	if s.blocked(ip) {
		back("busy")
		return
	}
	ck, err := r.Cookie(oauthCookie)
	s.setCookie(w, oauthCookie, "", "/auth/", -1)
	if err != nil {
		back("expired")
		return
	}
	part := strings.Split(ck.Value, ".")
	q := r.URL.Query()
	if len(part) != 4 || q.Get("code") == "" || subtle.ConstantTimeCompare([]byte(part[0]), []byte(q.Get("state"))) != 1 {
		s.note(ip)
		back("failed")
		return
	}
	c, err := g.exchange(q.Get("code"), part[1])
	if err != nil {
		log.Printf("google sign-in: %v", err)
		s.note(ip)
		back("failed")
		return
	}
	email := strings.ToLower(c.Email)
	switch {
	case c.Iss != "https://accounts.google.com" && c.Iss != "accounts.google.com", c.Aud != g.id, c.Exp < time.Now().Unix(),
		subtle.ConstantTimeCompare([]byte(c.Nonce), []byte(part[2])) != 1:
		log.Printf("google sign-in: token failed checks")
		s.note(ip)
		back("failed")
		return
	case !c.Verified || !g.emails[email]:
		log.Printf("google sign-in refused for %s", email)
		s.note(ip)
		back("denied")
		return
	}
	tok, now := random(32), time.Now()
	s.db.Exec(`DELETE FROM sessions WHERE expires<?`, now.UnixMilli())
	if _, err := s.db.Exec(`INSERT INTO sessions VALUES(?,?,?,?)`, hashToken(tok), email, now.UnixMilli(), now.AddDate(0, 0, sessionDays).UnixMilli()); err != nil {
		back("failed")
		return
	}
	s.setCookie(w, sessionCookie, tok, "/", sessionDays*86400)
	next, _ := base64.RawURLEncoding.DecodeString(part[3])
	http.Redirect(w, r, safeNext(string(next)), http.StatusFound)
}

func (s *server) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil {
		s.db.Exec(`DELETE FROM sessions WHERE token=?`, hashToken(c.Value))
	}
	s.setCookie(w, sessionCookie, "", "/", -1)
	w.WriteHeader(204)
}
