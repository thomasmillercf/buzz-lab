package main

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"math"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

//go:embed share.html
var sharePage string

const shareSchema = `CREATE TABLE IF NOT EXISTS shares(id INTEGER PRIMARY KEY,token TEXT NOT NULL UNIQUE,title TEXT NOT NULL,drives TEXT NOT NULL,created INTEGER NOT NULL,expires INTEGER NOT NULL,salt TEXT NOT NULL DEFAULT '',pin TEXT NOT NULL DEFAULT '',opens INTEGER NOT NULL DEFAULT 0);`

type share struct {
	ID      int64    `json:"id"`
	Title   string   `json:"title"`
	Drives  []string `json:"drives"`
	Created string   `json:"created"`
	Expires string   `json:"expires"`
	HasPin  bool     `json:"has_pin"`
	Route   bool     `json:"route_map"`
	Opens   int      `json:"opens"`
	salt    string
	pin     string
	view    string
}

// view is what the sharer was looking at when the link was made.
type view struct {
	Car   string `json:"car"`
	With  string `json:"with"`
	Place string `json:"place"`
	Lo    int    `json:"lo"`
	Hi    int    `json:"hi"`
}

func (v view) ok() bool {
	return len(v.Car) <= 80 && len(v.With) <= 80 && len(v.Place) <= 80 && v.Lo >= 0 && v.Hi <= 80 && v.Lo < v.Hi
}
func orNull(s string) string {
	if s == "" {
		return "null"
	}
	return s
}

func hashToken(t string) string { h := sha256.Sum256([]byte(t)); return hex.EncodeToString(h[:]) }
func hashPin(pin, salt string) string {
	k, err := pbkdf2.Key(sha256.New, pin, []byte(salt), 200000, 32)
	if err != nil {
		return ""
	}
	return hex.EncodeToString(k)
}
func random(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func (s *server) shareCreate(w http.ResponseWriter, r *http.Request) {
	var q struct {
		Title  string   `json:"title"`
		Drives []string `json:"drives"`
		Days   int      `json:"days"`
		Pin    string   `json:"pin"`
		View   *view    `json:"view"`
		Route  bool     `json:"route"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&q) != nil {
		fail(w, 400, "not valid JSON")
		return
	}
	q.Title = strings.TrimSpace(q.Title)
	if q.Title == "" || len(q.Title) > 80 || len(q.Drives) == 0 || len(q.Drives) > 200 || q.Days < 1 || q.Days > 365 {
		fail(w, 400, "needs a title, 1 to 200 drives, and 1 to 365 days")
		return
	}
	if q.Pin != "" && (len(q.Pin) < 4 || len(q.Pin) > 32) {
		fail(w, 400, "a PIN needs 4 to 32 characters")
		return
	}
	if q.Route && len(q.Drives) != 1 {
		fail(w, 400, "a route map can only be shared for a single drive")
		return
	}
	if q.View != nil && !q.View.ok() {
		fail(w, 400, "the view is not valid")
		return
	}
	for _, id := range q.Drives {
		var n int
		if !idRe.MatchString(id) || s.db.QueryRow(`SELECT COUNT(*) FROM drives WHERE id=?`, id).Scan(&n) != nil || n == 0 {
			fail(w, 400, "a drive is not on the server yet: "+id)
			return
		}
	}
	tok, salt, pin := random(32), "", ""
	if q.Pin != "" {
		salt = random(16)
		pin = hashPin(q.Pin, salt)
	}
	ds, _ := json.Marshal(q.Drives)
	vs := ""
	if q.View != nil {
		b, _ := json.Marshal(q.View)
		vs = string(b)
	}
	now := time.Now()
	x, err := s.db.Exec(`INSERT INTO shares(token,title,drives,created,expires,salt,pin,view,route) VALUES(?,?,?,?,?,?,?,?,?)`, hashToken(tok), q.Title, string(ds), now.UnixMilli(), now.AddDate(0, 0, q.Days).UnixMilli(), salt, pin, vs, q.Route)
	if err != nil {
		fail(w, 500, "database")
		return
	}
	id, _ := x.LastInsertId()
	writeJSON(w, 200, map[string]any{"id": id, "path": "/s/" + tok})
}

func (s *server) shareList(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(`SELECT id,title,drives,created,expires,pin,opens,route FROM shares ORDER BY id DESC`)
	if err != nil {
		fail(w, 500, "database")
		return
	}
	defer rows.Close()
	out := []share{}
	for rows.Next() {
		var h share
		var ds string
		var c, e int64
		if rows.Scan(&h.ID, &h.Title, &ds, &c, &e, &h.pin, &h.Opens, &h.Route) != nil {
			fail(w, 500, "database")
			return
		}
		json.Unmarshal([]byte(ds), &h.Drives)
		h.Created, h.Expires, h.HasPin = time.UnixMilli(c).UTC().Format(time.RFC3339), time.UnixMilli(e).UTC().Format(time.RFC3339), h.pin != ""
		out = append(out, h)
	}
	writeJSON(w, 200, map[string]any{"shares": out})
}

func (s *server) shareDelete(w http.ResponseWriter, r *http.Request) {
	x, err := s.db.Exec(`DELETE FROM shares WHERE id=?`, r.PathValue("id"))
	if err != nil {
		fail(w, 500, "database")
		return
	}
	if n, _ := x.RowsAffected(); n == 0 {
		fail(w, 404, "no such link")
		return
	}
	w.WriteHeader(204)
}

func (s *server) shareFind(tok string) (*share, bool) {
	var h share
	var ds string
	var e int64
	if len(tok) != 43 || s.db.QueryRow(`SELECT id,title,drives,expires,salt,pin,view,route FROM shares WHERE token=? AND expires>?`, hashToken(tok), time.Now().UnixMilli()).Scan(&h.ID, &h.Title, &ds, &e, &h.salt, &h.pin, &h.view, &h.Route) != nil {
		return nil, false
	}
	json.Unmarshal([]byte(ds), &h.Drives)
	h.Expires, h.HasPin = time.UnixMilli(e).UTC().Format("2 January 2006"), h.pin != ""
	return &h, true
}

// shareTrip gives a single shared drive its speed over time, and its route only when the sharer chose to include it.
func (s *server) shareTrip(h *share, out []shareDrive) any {
	if len(h.Drives) != 1 || len(out) != 1 {
		return nil
	}
	pts, err := s.readTrack(h.Drives[0])
	if err != nil || len(pts) < 2 {
		return nil
	}
	t0 := pts[0].T
	sp := make([][2]float64, 0, len(pts))
	var route [][3]float64
	for _, p := range pts {
		m := -1.0
		if p.Mph != nil {
			m = *p.Mph
		}
		sp = append(sp, [2]float64{math.Round((p.T-t0)*10) / 10, math.Round(m*10) / 10})
		if h.Route && p.Acc <= 40 && (p.Lat != 0 || p.Lon != 0) {
			route = append(route, [3]float64{math.Round(p.Lat*1e5) / 1e5, math.Round(p.Lon*1e5) / 1e5, math.Round(m*10) / 10})
		}
	}
	return map[string]any{"speed": sp, "route": route}
}

func shareHeaders(w http.ResponseWriter, nonce string) {
	h := w.Header()
	h.Set("Cache-Control", "no-store")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("X-Robots-Tag", "noindex, nofollow")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-"+nonce+"'; connect-src 'self'; img-src https://tile.openstreetmap.org; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
}

// shareView serves the page. It holds no results: those come from shareData, after any PIN.
func (s *server) shareView(w http.ResponseWriter, r *http.Request) {
	n := random(16)
	shareHeaders(w, n)
	ip := "share:" + clientIP(r)
	if s.blocked(ip) {
		w.WriteHeader(http.StatusTooManyRequests)
		io.WriteString(w, "Too many tries. Wait 10 minutes.")
		return
	}
	if _, ok := s.shareFind(r.PathValue("token")); !ok {
		s.note(ip)
		w.WriteHeader(404)
		io.WriteString(w, "This link doesn't exist or has expired.")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	io.WriteString(w, strings.ReplaceAll(sharePage, "{{NONCE}}", n))
}

type shareDrive struct {
	N       int               `json:"no"`
	Car     string            `json:"car"`
	Date    string            `json:"date"`
	Place   string            `json:"place"`
	Minutes float64           `json:"minutes"`
	Circ    float64           `json:"wheel_m,omitempty"`
	Strong  int               `json:"strong"`
	Weak    int               `json:"weak"`
	Gone    int               `json:"gone"`
	Feel    map[string]string `json:"felt,omitempty"`
	Count   []int             `json:"n"`
	PSD     [][]float64       `json:"psd"`
}

var tyreRe = regexp.MustCompile(`(\d{3})\s*/\s*(\d{2})\s*[Zz]?[Rr]\s*(\d{2})`)

func circ(t string) float64 {
	m := tyreRe.FindStringSubmatch(t)
	if m == nil {
		return 0
	}
	w, _ := strconv.ParseFloat(m[1], 64)
	a, _ := strconv.ParseFloat(m[2], 64)
	d, _ := strconv.ParseFloat(m[3], 64)
	return round(math.Pi * (d*25.4 + 2*w*a/100) / 1000 * .97)
}

// shareData never includes positions, plates, notes, raw readings, drive ids or times of day.
func (s *server) shareData(w http.ResponseWriter, r *http.Request) {
	shareHeaders(w, "none")
	ip := "share:" + clientIP(r)
	if s.blocked(ip) {
		fail(w, http.StatusTooManyRequests, "too many tries, wait 10 minutes")
		return
	}
	h, ok := s.shareFind(r.PathValue("token"))
	if !ok {
		s.note(ip)
		fail(w, 404, "this link doesn't exist or has expired")
		return
	}
	if h.HasPin {
		key := "pin:" + strconv.FormatInt(h.ID, 10)
		if s.blocked(key) {
			fail(w, http.StatusTooManyRequests, "too many wrong PINs, wait 10 minutes")
			return
		}
		var q struct {
			Pin string `json:"pin"`
		}
		json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&q)
		if q.Pin == "" {
			fail(w, 401, "pin")
			return
		}
		if subtle.ConstantTimeCompare([]byte(hashPin(q.Pin, h.salt)), []byte(h.pin)) != 1 {
			s.note(key)
			s.note(ip)
			fail(w, 401, "wrong pin")
			return
		}
	}
	all, err := s.load(query{to: math.MaxInt64, auto: -1}, "")
	if err != nil {
		fail(w, 500, "database")
		return
	}
	want := map[string]bool{}
	for _, id := range h.Drives {
		want[id] = true
	}
	out := []shareDrive{}
	for i := len(all) - 1; i >= 0; i-- { // oldest first
		d := all[i]
		if !want[d.ID] {
			continue
		}
		st := d.stats()
		x := shareDrive{N: len(out) + 1, Car: d.CarName, Date: time.UnixMilli(d.startMs).UTC().Format("2 January 2006"), Place: d.Place, Feel: d.Feel, Minutes: math.Round(d.Minutes*10) / 10, Circ: circ(d.Tyre), Count: st.N}
		for _, p := range st.PSD {
			q := make([]float64, len(p))
			for k, v := range p {
				if f, err := strconv.ParseFloat(strconv.FormatFloat(v, 'g', 4, 64), 64); err == nil {
					q[k] = f
				}
			}
			x.PSD = append(x.PSD, q)
		}
		for _, l := range strings.Split(d.Marks, "\n") {
			switch {
			case strings.HasSuffix(l, ",Strong"):
				x.Strong++
			case strings.HasSuffix(l, ",Weak"):
				x.Weak++
			case strings.HasSuffix(l, ",Gone"):
				x.Gone++
			}
		}
		out = append(out, x)
	}
	s.db.Exec(`UPDATE shares SET opens=opens+1 WHERE id=?`, h.ID)
	writeJSON(w, 200, map[string]any{"title": h.Title, "expires": h.Expires, "drives": out, "view": json.RawMessage(orNull(h.view)), "trip": s.shareTrip(h, out)})
}
