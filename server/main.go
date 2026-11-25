// Buzz Lab backend: one binary, SQLite, basic auth. See /llms.txt on a running server for the API.
package main

import (
	"archive/zip"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	_ "embed"
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed llms.txt
var llms string

const (
	maxUpload = 300 << 20
	f0, nf    = 2, 229 // PSD covers 2..230 Hz
	oldNF     = 119    // app 1.3 and earlier sent 2..120 Hz
	nb        = 16     // 5 mph bins from 0
)

type server struct {
	db         *sql.DB
	dir        string
	user, pass [32]byte
	mu         sync.Mutex
	fails      map[string][]time.Time
}

type meta struct {
	ID       string `json:"id"`
	Start    int64  `json:"start"`
	End      int64  `json:"end"`
	Car      string `json:"car"`
	Place    string `json:"place"`
	Note     string `json:"note"`
	Tyre     string `json:"tyre"`
	Imported string `json:"imported,omitempty"`
}

type stats struct {
	N      []int       `json:"n"`
	PSD    [][]float64 `json:"psd"`
	Ax     [][]float64 `json:"ax"`
	MaxMph float64     `json:"maxMph"`
	Km     float64     `json:"km"`
}

type car struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Tyre string `json:"tyre"`
}

var idRe = regexp.MustCompile(`^[0-9]{10,16}$`)

func open(dir string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", "file:"+filepath.Join(dir, "buzzlab.db")+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`CREATE TABLE IF NOT EXISTS cars(id TEXT PRIMARY KEY,name TEXT NOT NULL,tyre TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS drives(id TEXT PRIMARY KEY,car TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,place TEXT NOT NULL,note TEXT NOT NULL,tyre TEXT NOT NULL,stats TEXT NOT NULL,marks TEXT NOT NULL DEFAULT '',uploaded INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS drives_car ON drives(car,start);` + shareSchema)
	if err != nil {
		return nil, err
	}
	// added after the first release; an existing column is not an error
	if _, e := db.Exec(`ALTER TABLE shares ADD COLUMN view TEXT NOT NULL DEFAULT ''`); e != nil && !strings.Contains(e.Error(), "duplicate column") {
		return nil, e
	}
	return db, nil
}

func (s *server) auth(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" {
			io.WriteString(w, "ok\n")
			return
		}
		ip := clientIP(r)
		if s.blocked(ip) {
			fail(w, http.StatusTooManyRequests, "too many failed sign-ins, try again in 10 minutes")
			return
		}
		u, p, ok := r.BasicAuth()
		uh, ph := sha256.Sum256([]byte(u)), sha256.Sum256([]byte(p))
		good := subtle.ConstantTimeCompare(uh[:], s.user[:]) & subtle.ConstantTimeCompare(ph[:], s.pass[:])
		if !ok || good != 1 {
			if ok {
				s.note(ip)
			}
			w.Header().Set("WWW-Authenticate", `Basic realm="Buzz Lab", charset="UTF-8"`)
			fail(w, http.StatusUnauthorized, "sign in with basic auth")
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		h.ServeHTTP(w, r)
	})
}

// clientIP trusts the last X-Forwarded-For entry only when the direct peer is private (the reverse proxy).
func clientIP(r *http.Request) string {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	if ip := net.ParseIP(host); ip != nil && (ip.IsPrivate() || ip.IsLoopback()) {
		if x := strings.Split(r.Header.Get("X-Forwarded-For"), ","); strings.TrimSpace(x[len(x)-1]) != "" {
			return strings.TrimSpace(x[len(x)-1])
		}
	}
	return host
}
func (s *server) recent(ip string) []time.Time {
	var k []time.Time
	for _, t := range s.fails[ip] {
		if time.Since(t) < 10*time.Minute {
			k = append(k, t)
		}
	}
	return k
}
func (s *server) blocked(ip string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.recent(ip)) >= 10
}
func (s *server) note(ip string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.fails) > 5000 {
		s.fails = map[string][]time.Time{}
	}
	s.fails[ip] = append(s.recent(ip), time.Now())
}

func fail(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}
func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

// upload takes one app export zip holding any number of drives. Re-sending a drive replaces it.
func (s *server) upload(w http.ResponseWriter, r *http.Request) {
	tmp, err := os.CreateTemp(s.dir, "up-*.zip")
	if err != nil {
		fail(w, 500, "storage")
		return
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()
	size, err := io.Copy(tmp, http.MaxBytesReader(w, r.Body, maxUpload))
	if err != nil {
		var big *http.MaxBytesError
		if errors.As(err, &big) {
			fail(w, http.StatusRequestEntityTooLarge, "upload is over 300 MB")
			return
		}
		fail(w, 400, "upload was cut short")
		return
	}
	z, err := zip.NewReader(tmp, size)
	if err != nil {
		fail(w, 400, "not a zip")
		return
	}
	ids, err := s.ingest(z)
	if err != nil {
		fail(w, 400, err.Error())
		return
	}
	if len(ids) == 1 { // keep the raw readings for a single-drive upload
		tmp.Close()
		if err := os.Rename(tmp.Name(), s.raw(ids[0])); err != nil {
			fail(w, 500, "storage")
			return
		}
	}
	writeJSON(w, 200, map[string]any{"saved": ids})
}
func (s *server) raw(id string) string { return filepath.Join(s.dir, "raw", id+".zip") }

func readZip(z *zip.Reader, name string, limit int64) ([]byte, error) {
	f, err := z.Open(name)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err == nil && int64(len(b)) > limit {
		err = fmt.Errorf("%s is too large", name)
	}
	return b, err
}

func (s *server) ingest(z *zip.Reader) ([]string, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	if b, err := readZip(z, "cars.json", 1<<20); err == nil {
		var cs []car
		if json.Unmarshal(b, &cs) != nil {
			return nil, errors.New("cars.json is not valid")
		}
		for _, c := range cs {
			if _, err := tx.Exec(`INSERT INTO cars VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,tyre=excluded.tyre`, c.ID, c.Name, c.Tyre); err != nil {
				return nil, err
			}
		}
	}
	ids := []string{}
	for _, f := range z.File {
		p := strings.Split(f.Name, "/")
		if len(p) != 3 || p[0] != "drives" || p[2] != "meta.json" {
			continue
		}
		mb, err := readZip(z, f.Name, 1<<20)
		if err != nil {
			return nil, err
		}
		var m meta
		if json.Unmarshal(mb, &m) != nil || !idRe.MatchString(m.ID) || m.ID != p[1] {
			return nil, errors.New("a drive has a bad meta.json")
		}
		sb, err := readZip(z, "drives/"+m.ID+"/stats.json", 4<<20)
		if err != nil {
			return nil, errors.New("drive " + m.ID + " has no stats.json")
		}
		var st stats
		if json.Unmarshal(sb, &st) != nil || !st.valid() {
			return nil, errors.New("drive " + m.ID + " has a bad stats.json")
		}
		mk, _ := readZip(z, "drives/"+m.ID+"/marks.csv", 1<<20)
		if _, err := tx.Exec(`INSERT INTO drives VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET car=excluded.car,start=excluded.start,end=excluded.end,place=excluded.place,note=excluded.note,tyre=excluded.tyre,stats=excluded.stats,marks=excluded.marks,uploaded=excluded.uploaded`,
			m.ID, m.Car, m.Start, m.End, m.Place, m.Note, m.Tyre, string(sb), string(mk), time.Now().UnixMilli()); err != nil {
			return nil, err
		}
		ids = append(ids, m.ID)
	}
	if len(ids) == 0 {
		return nil, errors.New("no drives in this zip")
	}
	return ids, tx.Commit()
}

func (s stats) valid() bool {
	if len(s.N) != nb || len(s.PSD) != nb || len(s.Ax) != nb {
		return false
	}
	for b := range s.N {
		if s.N[b] > 0 && ((len(s.PSD[b]) != nf && len(s.PSD[b]) != oldNF) || len(s.Ax[b]) != 3) {
			return false
		}
	}
	return true
}

type drive struct {
	ID       string   `json:"id"`
	Car      string   `json:"car"`
	CarName  string   `json:"car_name"`
	Start    string   `json:"start"`
	Minutes  float64  `json:"minutes"`
	Place    string   `json:"place"`
	Note     string   `json:"note,omitempty"`
	Tyre     string   `json:"tyre,omitempty"`
	Marks    string   `json:"marks_csv,omitempty"`
	HasRaw   bool     `json:"has_raw"`
	Summary  *summary `json:"summary,omitempty"`
	startMs  int64
	statsRaw string
}

type summary struct {
	SpeedMph      [2]int             `json:"speed_mph"`
	SteadySeconds int                `json:"steady_seconds"`
	PeakHz        int                `json:"peak_hz_above_15"`
	BandRMS       map[string]float64 `json:"band_rms_ms2"`
	ForeAft       float64            `json:"phone_length_share_25_35hz"`
	Spectrum      map[string]float64 `json:"amplitude_by_hz,omitempty"`
	BySpeed       []speedRow         `json:"by_speed,omitempty"`
}
type speedRow struct {
	Mph     [2]int  `json:"mph"`
	Seconds int     `json:"seconds"`
	PeakHz  int     `json:"peak_hz_above_15"`
	RMS     float64 `json:"rms_25_35hz"`
}

func round(v float64) float64 { return math.Round(v*10000) / 10000 }
func add(a *stats, b stats) {
	for i := 0; i < nb; i++ {
		if b.N[i] == 0 {
			continue
		}
		if len(b.PSD[i]) < len(a.PSD[i]) { // keep only the range every drive has
			for k := range a.PSD {
				a.PSD[k] = a.PSD[k][:len(b.PSD[i])]
			}
		}
		a.N[i] += b.N[i]
		for k := range a.PSD[i] {
			a.PSD[i][k] += b.PSD[i][k]
		}
		for k := range b.Ax[i] {
			a.Ax[i][k] += b.Ax[i][k]
		}
	}
}
func empty() stats {
	s := stats{N: make([]int, nb), PSD: make([][]float64, nb), Ax: make([][]float64, nb)}
	for i := range s.N {
		s.PSD[i], s.Ax[i] = make([]float64, nf), make([]float64, 3)
	}
	return s
}
func band(p []float64, n float64, a, b int) float64 {
	t := 0.0
	for h := a; h < b; h++ {
		t += p[h-f0]
	}
	return round(math.Sqrt(t / n))
}
func peak(p []float64) int {
	k := 15
	for h := 15; h <= 100; h++ {
		if p[h-f0] > p[k-f0] {
			k = h
		}
	}
	return k
}
func summarise(s stats, lo, hi int, full bool) *summary {
	l := nf
	for b := range s.N {
		if s.N[b] > 0 && len(s.PSD[b]) < l {
			l = len(s.PSD[b])
		}
	}
	p, ax, n := make([]float64, l), [3]float64{}, 0
	o := &summary{SpeedMph: [2]int{lo, hi}, BandRMS: map[string]float64{}}
	for b := 0; b < nb; b++ {
		if b*5 < lo || b*5+5 > hi || s.N[b] == 0 {
			continue
		}
		n += s.N[b]
		for k, v := range s.PSD[b][:l] {
			p[k] += v
		}
		for k, v := range s.Ax[b] {
			ax[k] += v
		}
		if full {
			o.BySpeed = append(o.BySpeed, speedRow{[2]int{b * 5, b*5 + 5}, s.N[b] * 2, peak(s.PSD[b]), band(s.PSD[b], float64(s.N[b]), 25, 35)})
		}
	}
	if n == 0 {
		return o
	}
	o.SteadySeconds, o.PeakHz = n*2, peak(p)
	for _, x := range [][2]int{{2, 8}, {8, 20}, {25, 35}, {38, 48}, {50, 80}, {80, 120}, {120, 230}} {
		if x[1] > l+f0 {
			continue
		}
		o.BandRMS[fmt.Sprintf("%d-%d", x[0], x[1])] = band(p, float64(n), x[0], x[1])
	}
	if t := ax[0] + ax[1] + ax[2]; t > 0 {
		o.ForeAft = round(ax[1] / t)
	}
	if full {
		o.Spectrum = map[string]float64{}
		for k, v := range p {
			o.Spectrum[strconv.Itoa(k+f0)] = round(math.Sqrt(v / float64(n)))
		}
	}
	return o
}

type query struct {
	car, place string
	from, to   int64
	lo, hi     int
}

func parse(r *http.Request) (query, error) {
	v, q := r.URL.Query(), query{to: math.MaxInt64, lo: 10, hi: 80}
	q.car, q.place = v.Get("car"), v.Get("place")
	for k, d := range map[string]*int64{"from": &q.from, "to": &q.to} {
		if x := v.Get(k); x != "" {
			t, err := time.Parse("2006-01-02", x)
			if err != nil {
				return q, fmt.Errorf("%s must be a date like 2023-11-14", k)
			}
			if k == "to" {
				t = t.AddDate(0, 0, 1).Add(-time.Millisecond)
			}
			*d = t.UnixMilli()
		}
	}
	for k, d := range map[string]*int{"min_mph": &q.lo, "max_mph": &q.hi} {
		if x := v.Get(k); x != "" {
			n, err := strconv.Atoi(x)
			if err != nil || n < 0 || n > 80 || n%5 != 0 {
				return q, fmt.Errorf("%s must be 0 to 80 in steps of 5", k)
			}
			*d = n
		}
	}
	return q, nil
}
func (s *server) load(q query, id string) ([]drive, error) {
	rows, err := s.db.Query(`SELECT d.id,d.car,COALESCE(c.name,d.car),d.start,d.end,d.place,d.note,COALESCE(NULLIF(d.tyre,''),c.tyre,''),d.stats,d.marks FROM drives d LEFT JOIN cars c ON c.id=d.car
WHERE (?='' OR d.id=?) AND (?='' OR d.car=? OR c.name=? COLLATE NOCASE) AND (?='' OR d.place=?) AND d.start BETWEEN ? AND ? ORDER BY d.start DESC`, id, id, q.car, q.car, q.car, q.place, q.place, q.from, q.to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []drive{}
	for rows.Next() {
		var d drive
		var end int64
		if err := rows.Scan(&d.ID, &d.Car, &d.CarName, &d.startMs, &end, &d.Place, &d.Note, &d.Tyre, &d.statsRaw, &d.Marks); err != nil {
			return nil, err
		}
		d.Start, d.Minutes = time.UnixMilli(d.startMs).UTC().Format(time.RFC3339), round(float64(end-d.startMs)/60000)
		out = append(out, d)
	}
	for i := range out {
		_, e := os.Stat(s.raw(out[i].ID))
		out[i].HasRaw = e == nil
	}
	return out, rows.Err()
}
func (d drive) stats() stats { var s stats; json.Unmarshal([]byte(d.statsRaw), &s); return s }

func (s *server) list(w http.ResponseWriter, r *http.Request) {
	q, err := parse(r)
	if err != nil {
		fail(w, 400, err.Error())
		return
	}
	ds, err := s.load(q, "")
	if err != nil {
		fail(w, 500, "database")
		return
	}
	for i := range ds {
		ds[i].Summary, ds[i].Marks = summarise(ds[i].stats(), q.lo, q.hi, false), ""
	}
	writeJSON(w, 200, map[string]any{"drives": ds})
}
func (s *server) one(w http.ResponseWriter, r *http.Request) {
	q, err := parse(r)
	if err != nil {
		fail(w, 400, err.Error())
		return
	}
	ds, err := s.load(query{to: math.MaxInt64}, r.PathValue("id"))
	if err != nil {
		fail(w, 500, "database")
		return
	}
	if !idRe.MatchString(r.PathValue("id")) || len(ds) == 0 {
		fail(w, 404, "no such drive")
		return
	}
	ds[0].Summary = summarise(ds[0].stats(), q.lo, q.hi, true)
	writeJSON(w, 200, ds[0])
}
func (s *server) average(w http.ResponseWriter, r *http.Request) {
	q, err := parse(r)
	if err == nil && q.car == "" {
		err = errors.New("car is required")
	}
	if err != nil {
		fail(w, 400, err.Error())
		return
	}
	ds, err := s.load(q, "")
	if err != nil {
		fail(w, 500, "database")
		return
	}
	t := empty()
	for _, d := range ds {
		add(&t, d.stats())
	}
	writeJSON(w, 200, map[string]any{"car": q.car, "drives": len(ds), "summary": summarise(t, q.lo, q.hi, true)})
}
func (s *server) cars(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(`SELECT c.id,c.name,c.tyre,(SELECT COUNT(*) FROM drives d WHERE d.car=c.id) FROM cars c ORDER BY c.name`)
	if err != nil {
		fail(w, 500, "database")
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var c car
		var n int
		rows.Scan(&c.ID, &c.Name, &c.Tyre, &n)
		out = append(out, map[string]any{"id": c.ID, "name": c.Name, "tyre": c.Tyre, "drives": n})
	}
	writeJSON(w, 200, map[string]any{"cars": out})
}
func (s *server) rawGet(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !idRe.MatchString(id) {
		fail(w, 404, "no such drive")
		return
	}
	if _, err := os.Stat(s.raw(id)); err != nil {
		fail(w, 404, "no raw readings for this drive")
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="`+id+`.zip"`)
	http.ServeFile(w, r, s.raw(id))
}

type point struct {
	T   float64  `json:"t"`
	Lat float64  `json:"lat"`
	Lon float64  `json:"lon"`
	Mph *float64 `json:"mph"`
	Acc float64  `json:"accuracy_m"`
}

// track reads positions from the raw upload; drives sent without positions have none.
func (s *server) track(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !idRe.MatchString(id) {
		fail(w, 404, "no such drive")
		return
	}
	z, err := zip.OpenReader(s.raw(id))
	if err != nil {
		fail(w, 404, "no raw readings for this drive")
		return
	}
	defer z.Close()
	b, err := readZip(&z.Reader, "drives/"+id+"/Location.csv", 32<<20)
	if err != nil {
		fail(w, 404, "no positions for this drive")
		return
	}
	c := csv.NewReader(strings.NewReader(string(b)))
	c.FieldsPerRecord = -1
	rows, err := c.ReadAll()
	if err != nil {
		fail(w, 500, "positions could not be read")
		return
	}
	num := func(x string) (float64, bool) {
		v, err := strconv.ParseFloat(x, 64)
		return v, err == nil && !math.IsNaN(v) && !math.IsInf(v, 0)
	}
	out := []point{}
	for _, f := range rows {
		if len(f) < 7 {
			continue
		}
		t, a := num(f[0])
		lat, b := num(f[1])
		lon, c := num(f[2])
		if !a || !b || !c {
			continue
		}
		p := point{T: t, Lat: lat, Lon: lon}
		if v, ok := num(f[4]); ok {
			m := round(v * 2.23694)
			p.Mph = &m
		}
		p.Acc, _ = num(f[6])
		if math.IsNaN(p.Acc) || math.IsInf(p.Acc, 0) {
			p.Acc = 0
		}
		out = append(out, p)
	}
	writeJSON(w, 200, map[string]any{"id": id, "points": out})
}

// export streams everything: a consistent copy of the database plus every raw upload.
func (s *server) export(w http.ResponseWriter, r *http.Request) {
	d, err := os.MkdirTemp(s.dir, "ex-")
	if err != nil {
		fail(w, 500, "storage")
		return
	}
	defer os.RemoveAll(d)
	snap := filepath.Join(d, "buzzlab.db")
	if _, err := s.db.Exec(`VACUUM INTO ?`, snap); err != nil {
		fail(w, 500, "database")
		return
	}
	raws, _ := filepath.Glob(filepath.Join(s.dir, "raw", "*.zip"))
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="buzzlab-`+time.Now().UTC().Format("2006-01-02")+`.zip"`)
	z := zip.NewWriter(w)
	for _, f := range append([]string{snap}, raws...) {
		n, m := "raw/"+filepath.Base(f), zip.Store // raws are already compressed
		if f == snap {
			n, m = "buzzlab.db", zip.Deflate
		}
		h, err := z.CreateHeader(&zip.FileHeader{Name: n, Method: m, Modified: time.Now()})
		if err != nil {
			return
		}
		in, err := os.Open(f)
		if err != nil {
			return
		}
		_, err = io.Copy(h, in)
		in.Close()
		if err != nil {
			return // leaves the zip unfinished, so a cut-short download fails to open
		}
	}
	z.Close()
}
func (s *server) del(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !idRe.MatchString(id) {
		fail(w, 404, "no such drive")
		return
	}
	x, err := s.db.Exec(`DELETE FROM drives WHERE id=?`, id)
	if err != nil {
		fail(w, 500, "database")
		return
	}
	os.Remove(s.raw(id))
	if n, _ := x.RowsAffected(); n == 0 {
		fail(w, 404, "no such drive")
		return
	}
	w.WriteHeader(204)
}

func (s *server) routes() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/llms.txt", http.StatusFound) })
	m.HandleFunc("GET /llms.txt", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		io.WriteString(w, llms)
	})
	m.HandleFunc("POST /api/upload", s.upload)
	m.HandleFunc("GET /api/cars", s.cars)
	m.HandleFunc("GET /api/drives", s.list)
	m.HandleFunc("GET /api/drives/{id}", s.one)
	m.HandleFunc("GET /api/drives/{id}/raw.zip", s.rawGet)
	m.HandleFunc("GET /api/drives/{id}/track", s.track)
	m.HandleFunc("POST /api/shares", s.shareCreate)
	m.HandleFunc("GET /api/shares", s.shareList)
	m.HandleFunc("DELETE /api/shares/{id}", s.shareDelete)
	m.HandleFunc("GET /api/export.zip", s.export)
	m.HandleFunc("GET /app.apk", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", `attachment; filename="buzzlab.apk"`)
		http.ServeFile(w, r, env("BUZZ_APK", "/buzzlab.apk"))
	})
	m.HandleFunc("DELETE /api/drives/{id}", s.del)
	m.HandleFunc("GET /api/average", s.average)
	pub := http.NewServeMux() // reachable without the login, only by its random key
	pub.HandleFunc("GET /s/{token}", s.shareView)
	pub.HandleFunc("POST /s/{token}/data", s.shareData)
	private := s.auth(m)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/s/") {
			pub.ServeHTTP(w, r)
			return
		}
		private.ServeHTTP(w, r)
	})
}

func newServer(dir, user, pass string) (*server, error) {
	if user == "" || len(pass) < 16 {
		return nil, errors.New("set BUZZ_USER, and BUZZ_PASS with at least 16 characters")
	}
	if err := os.MkdirAll(filepath.Join(dir, "raw"), 0o700); err != nil {
		return nil, err
	}
	db, err := open(dir)
	if err != nil {
		return nil, err
	}
	return &server{db: db, dir: dir, user: sha256.Sum256([]byte(user)), pass: sha256.Sum256([]byte(pass)), fails: map[string][]time.Time{}}, nil
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "-health" { // container health check, no shell needed
		c := http.Client{Timeout: 2 * time.Second}
		r, err := c.Get("http://127.0.0.1" + env("BUZZ_ADDR", ":8095") + "/healthz")
		if err != nil || r.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	s, err := newServer(env("BUZZ_DATA", "./data"), os.Getenv("BUZZ_USER"), os.Getenv("BUZZ_PASS"))
	if err != nil {
		log.Fatal(err)
	}
	h := &http.Server{Addr: env("BUZZ_ADDR", "127.0.0.1:8095"), Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	log.Printf("listening on %s", h.Addr)
	log.Fatal(h.ListenAndServe())
}
