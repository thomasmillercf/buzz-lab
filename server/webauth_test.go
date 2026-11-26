package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// fakeGoogle stands in for Google's token endpoint and hands back whatever claims the test sets.
func fakeGoogle(t *testing.T, claims *map[string]any, gotVerifier *string) *httptest.Server {
	g := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		*gotVerifier = r.PostForm.Get("code_verifier")
		if r.PostForm.Get("client_secret") != "sec" || r.PostForm.Get("code") != "the-code" {
			w.WriteHeader(400)
			return
		}
		b, _ := json.Marshal(*claims)
		tok := "eyJhbGciOiJSUzI1NiJ9." + base64.RawURLEncoding.EncodeToString(b) + ".sig"
		json.NewEncoder(w).Encode(map[string]string{"id_token": tok})
	}))
	t.Cleanup(g.Close)
	return g
}

type browser struct {
	h       http.Handler
	cookies map[string]*http.Cookie
}

func (b *browser) do(m, p string, hdr map[string]string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(m, p, nil)
	for k, v := range hdr {
		r.Header.Set(k, v)
	}
	for _, c := range b.cookies {
		r.AddCookie(c)
	}
	w := httptest.NewRecorder()
	b.h.ServeHTTP(w, r)
	for _, c := range w.Result().Cookies() {
		if c.MaxAge < 0 {
			delete(b.cookies, c.Name)
		} else {
			b.cookies[c.Name] = c
		}
	}
	return w
}

// signIn runs the whole redirect dance and returns where the callback sent the browser.
func (b *browser) signIn(t *testing.T, claims *map[string]any, state string) string {
	return b.signInFrom(t, "/auth/google", claims, state)
}
func (b *browser) signInFrom(t *testing.T, start string, claims *map[string]any, state string) string {
	w := b.do("GET", start, nil)
	if w.Code != 302 {
		t.Fatal("start", w.Code)
	}
	u, _ := url.Parse(w.Header().Get("Location"))
	q := u.Query()
	if u.Host != "accounts.google.com" || q.Get("code_challenge_method") != "S256" || q.Get("redirect_uri") != "https://buzz.example/auth/callback" {
		t.Fatal("bad redirect", u)
	}
	(*claims)["nonce"] = q.Get("nonce")
	if state == "" {
		state = q.Get("state")
	}
	w = b.do("GET", "/auth/callback?code=the-code&state="+url.QueryEscape(state), nil)
	return w.Header().Get("Location")
}

func TestGoogleSignIn(t *testing.T) {
	s, h := mk(t)
	var verifier string
	claims := map[string]any{}
	g := fakeGoogle(t, &claims, &verifier)
	s.google = newGoogle("cid", "sec", g.URL, "https://buzz.example/", " Me@Example.com ,other@example.com")
	good := func() {
		claims = map[string]any{"iss": "https://accounts.google.com", "aud": "cid", "exp": time.Now().Add(time.Hour).Unix(), "email": "me@example.com", "email_verified": true}
	}
	if c := do(h, "POST", "/api/upload", export("1700002000000", 40, .02), "u", pw).Code; c != 200 {
		t.Fatal("upload", c)
	}

	b := &browser{h: h, cookies: map[string]*http.Cookie{}}
	// a browser with no session is sent to sign in; the app still gets the basic-auth challenge
	if w := b.do("GET", "/", map[string]string{"Accept": "text/html"}); w.Code != 302 || w.Header().Get("Location") != "/login" {
		t.Fatal("no redirect", w.Code)
	}
	if w := b.do("GET", "/api/cars", nil); w.Code != 401 || w.Header().Get("WWW-Authenticate") == "" {
		t.Fatal("app challenge", w.Code)
	}
	if w := b.do("GET", "/api/view", map[string]string{"X-Buzz-Web": "1"}); w.Code != 401 || w.Header().Get("WWW-Authenticate") != "" {
		t.Fatal("web page should get a plain 401", w.Code)
	}
	if w := b.do("GET", "/login", nil); w.Code != 200 || !strings.Contains(w.Body.String(), "Sign in with Google") {
		t.Fatal("login page", w.Code)
	}

	// someone else's account, a replayed nonce, a forged state and a wrong audience are all refused
	good()
	claims["email"] = "stranger@example.com"
	if l := b.signIn(t, &claims, ""); l != "/login?e=denied" {
		t.Fatal("stranger", l)
	}
	good()
	claims["email_verified"] = false
	if l := b.signIn(t, &claims, ""); l != "/login?e=denied" {
		t.Fatal("unverified", l)
	}
	good()
	if l := b.signIn(t, &claims, "forged"); l != "/login?e=failed" {
		t.Fatal("state", l)
	}
	good()
	claims["aud"] = "someone-else"
	if l := b.signIn(t, &claims, ""); l != "/login?e=failed" {
		t.Fatal("aud", l)
	}
	if _, ok := b.cookies[sessionCookie]; ok {
		t.Fatal("session set after a refusal")
	}
	if w := b.do("GET", "/auth/callback?code=the-code&state=x", nil); w.Header().Get("Location") != "/login?e=expired" {
		t.Fatal("no oauth cookie", w.Header().Get("Location"))
	}

	// the right account, with the address in another case
	good()
	claims["email"] = "ME@example.com"
	s.mu.Lock()
	s.fails = map[string][]time.Time{}
	s.mu.Unlock()
	if l := b.signIn(t, &claims, ""); l != "/" {
		t.Fatal("sign in", l)
	}
	if len(verifier) < 40 {
		t.Fatal("no PKCE verifier sent")
	}
	c := b.cookies[sessionCookie]
	if c == nil || !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteLaxMode {
		t.Fatalf("session cookie %+v", c)
	}
	if w := b.do("GET", "/", map[string]string{"Accept": "text/html"}); w.Code != 200 || !strings.Contains(w.Body.String(), "const DASH=true") {
		t.Fatal("dashboard", w.Code)
	}
	w := b.do("GET", "/api/view", map[string]string{"X-Buzz-Web": "1"})
	var v struct {
		User   string
		Drives []shareDrive
		Trip   *struct{ Route [][3]float64 }
	}
	json.Unmarshal(w.Body.Bytes(), &v)
	if w.Code != 200 || v.User != "me@example.com" || len(v.Drives) != 1 || v.Drives[0].ID != "1700002000000" || v.Trip != nil {
		t.Fatal("view", w.Code, w.Body.String())
	}
	w = b.do("GET", "/api/view?drive=1700002000000", nil)
	json.Unmarshal(w.Body.Bytes(), &v)
	if w.Code != 200 || v.Trip == nil || len(v.Trip.Route) == 0 {
		t.Fatal("one drive has no route", w.Body.String())
	}
	if c := b.do("GET", "/api/view?drive=1", nil).Code; c != 404 {
		t.Fatal(c)
	}
	// changes need the header a page on another site can't add
	if c := b.do("DELETE", "/api/drives/1700002000000", nil).Code; c != 403 {
		t.Fatal("cookie-only delete allowed", c)
	}
	if c := b.do("POST", "/auth/logout", nil).Code; c != 204 {
		t.Fatal(c)
	}
	if c := b.do("GET", "/api/cars", nil).Code; c != 401 {
		t.Fatal("still signed in", c)
	}
	// a link that needed sign-in, like the app download, comes back to where it started
	w = b.do("GET", "/app.apk", map[string]string{"Accept": "text/html"})
	if l := w.Header().Get("Location"); l != "/login?next=%2Fapp.apk" {
		t.Fatal("no next", l)
	}
	if p := b.do("GET", "/login?next=%2Fapp.apk", nil).Body.String(); !strings.Contains(p, `href="/auth/google?next=%2Fapp.apk"`) {
		t.Fatal("login link drops next")
	}
	good()
	if l := b.signInFrom(t, "/auth/google?next=%2Fapp.apk", &claims, ""); l != "/app.apk" {
		t.Fatal("next not kept", l)
	}
	for _, bad := range []string{"//evil.example/x", "https://evil.example/", "/\\evil.example", "/auth/google", "/login", "x"} {
		good()
		if l := b.signInFrom(t, "/auth/google?next="+url.QueryEscape(bad), &claims, ""); l != "/" {
			t.Fatal("unsafe next followed", bad, l)
		}
	}
	if !strings.Contains(b.do("GET", "/", nil).Body.String(), `href="/app.apk"`) {
		t.Fatal("no install link")
	}
	b.do("POST", "/auth/logout", nil)
	// the stolen cookie is dead too
	b.cookies[sessionCookie] = c
	if c := b.do("GET", "/api/cars", nil).Code; c != 401 {
		t.Fatal("old cookie works", c)
	}
}

func TestSharesHideIDs(t *testing.T) {
	_, h := mk(t)
	do(h, "POST", "/api/upload", export("1700002000000", 40, .02), "u", pw)
	w := do(h, "POST", "/api/shares", []byte(`{"title":"T","drives":["1700002000000"],"days":1}`), "u", pw)
	var sh struct{ Path string }
	json.Unmarshal(w.Body.Bytes(), &sh)
	w = do(h, "POST", sh.Path+"/data", []byte(`{}`), "", "")
	b, _ := io.ReadAll(w.Body)
	if w.Code != 200 || strings.Contains(string(b), "1700002000000") || strings.Contains(string(b), "user") {
		t.Fatal(string(b))
	}
	if p := do(h, "GET", sh.Path, nil, "", "").Body.String(); !strings.Contains(p, "const DASH=false") {
		t.Fatal("share page mode")
	}
}

func TestNoGoogle(t *testing.T) {
	_, h := mk(t)
	if w := do(h, "GET", "/login", nil, "", ""); !strings.Contains(w.Body.String(), "isn't set up") {
		t.Fatal(w.Body.String())
	}
	// without Google, a browser still gets the password box
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set("Accept", "text/html")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
	if c := do(h, "GET", "/", nil, "u", pw).Code; c != 200 {
		t.Fatal("basic auth dashboard", c)
	}
}

func TestAppSignIn(t *testing.T) {
	s, h := mk(t)
	var verifier string
	claims := map[string]any{}
	g := fakeGoogle(t, &claims, &verifier)
	s.google = newGoogle("cid", "sec", g.URL, "https://buzz.example", "me@example.com")
	claims = map[string]any{"iss": "https://accounts.google.com", "aud": "cid", "exp": time.Now().Add(time.Hour).Unix(), "email": "me@example.com", "email_verified": true}

	pkce := "a-verifier-the-phone-made-and-kept-to-itself-0123456789"
	sum := sha256.Sum256([]byte(pkce))
	ch := base64.RawURLEncoding.EncodeToString(sum[:])
	start := "/auth/app?c=" + ch + "&name=" + url.QueryEscape("Pixel <b>9</b>")

	b := &browser{h: h, cookies: map[string]*http.Cookie{}}
	// not signed in: sign in first, then come back here
	if l := b.do("GET", start, nil).Header().Get("Location"); l != "/login?next="+url.QueryEscape(start) {
		t.Fatal("ask without session", l)
	}
	if l := b.signInFrom(t, "/auth/google?next="+url.QueryEscape(start), &claims, ""); l != start {
		t.Fatal("not sent back to the app page", l)
	}
	w := b.do("GET", start, nil)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Pixel &lt;b&gt;9&lt;/b&gt;") || !strings.Contains(w.Header().Get("Content-Security-Policy"), "form-action 'self' buzzlab:") {
		t.Fatal("ask page", w.Code, w.Body.String())
	}
	if c := b.do("GET", "/auth/app?c=short", nil).Code; c != 400 {
		t.Fatal("bad challenge", c)
	}

	allow := func(origin string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/auth/app", strings.NewReader("c="+ch+"&name=Pixel"))
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		if origin != "" {
			r.Header.Set("Origin", origin)
		}
		for _, c := range b.cookies {
			r.AddCookie(c)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	if c := allow("https://evil.example").Code; c != 403 {
		t.Fatal("cross-site allow", c)
	}
	w = allow("https://buzz.example")
	loc, _ := url.Parse(w.Header().Get("Location"))
	if w.Code != 303 || loc.Scheme != "buzzlab" || loc.Host != "signin" {
		t.Fatal("allow", w.Code, loc)
	}
	code := loc.Query().Get("code")

	swap := func(code, v string) *httptest.ResponseRecorder {
		b, _ := json.Marshal(map[string]string{"code": code, "verifier": v})
		return do(h, "POST", "/auth/app/token", b, "", "")
	}
	// a stolen code is useless without the phone's verifier, and it is used up by the try
	if c := swap(code, strings.Repeat("x", 50)).Code; c != 400 {
		t.Fatal("wrong verifier accepted", c)
	}
	if c := swap(code, pkce).Code; c != 400 {
		t.Fatal("code used twice", c)
	}
	loc, _ = url.Parse(allow("").Header().Get("Location"))
	w = swap(loc.Query().Get("code"), pkce)
	var got struct{ Token, Email string }
	json.Unmarshal(w.Body.Bytes(), &got)
	if w.Code != 200 || len(got.Token) != 43 || got.Email != "me@example.com" {
		t.Fatal("swap", w.Code, w.Body.String())
	}

	key := func(m, p string, body []byte) int {
		r := httptest.NewRequest(m, p, bytes.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+got.Token)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	if c := key("POST", "/api/upload", export("1700002000000", 40, .02)); c != 200 {
		t.Fatal("upload with key", c)
	}
	if c := key("GET", "/api/cars", nil); c != 200 {
		t.Fatal("key", c)
	}
	// leaving the allowlist ends the key
	delete(s.google.emails, "me@example.com")
	if c := key("GET", "/api/cars", nil); c != 401 {
		t.Fatal("key outlived allowlist", c)
	}
	s.google.emails["me@example.com"] = true
	if c := key("POST", "/auth/app/signout", nil); c != 204 {
		t.Fatal(c)
	}
	if c := key("GET", "/api/cars", nil); c != 401 {
		t.Fatal("key works after sign-out", c)
	}
}
