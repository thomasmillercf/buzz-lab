package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

const pw = "correct horse battery"

func mk(t *testing.T) (*server, http.Handler) {
	s, err := newServer(t.TempDir(), "u", pw)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.db.Close() })
	return s, s.routes()
}
func export(id string, n int, lvl float64) []byte {
	st := empty()
	st.N[5] = n
	for k := range st.PSD[5] {
		st.PSD[5][k] = .0001 * float64(n)
	}
	st.PSD[5][30-f0] = lvl * float64(n)
	st.Ax[5] = []float64{1, 8, 1}
	sj, _ := json.Marshal(st)
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	for k, v := range map[string]string{"cars.json": `[{"id":"c1","name":"Hatchback","tyre":""}]`, "drives/" + id + "/meta.json": `{"id":"` + id + `","car":"c1","start":` + id + `,"end":` + id + `,"place":"Seat rail, taped"}`, "drives/" + id + "/stats.json": string(sj), "drives/" + id + "/Location.csv": "h,h,h,h,h,h,h,h\n0.5,1.5,-0.5,NaN,10,NaN,4,NaN\n1.5,NaN,NaN,NaN,10,NaN,4,NaN\n2.5,1.6,-0.6,NaN,NaN,NaN,4,NaN\n"} {
		w, _ := z.Create(k)
		io.WriteString(w, v)
	}
	z.Close()
	return b.Bytes()
}
func do(h http.Handler, m, p string, body []byte, user, pass string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(m, p, bytes.NewReader(body))
	if user != "" {
		r.SetBasicAuth(user, pass)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func TestAuth(t *testing.T) {
	_, h := mk(t)
	if c := do(h, "GET", "/api/cars", nil, "", "").Code; c != 401 {
		t.Fatal(c)
	}
	if c := do(h, "GET", "/healthz", nil, "", "").Code; c != 200 {
		t.Fatal(c)
	}
	if c := do(h, "GET", "/api/cars", nil, "u", pw).Code; c != 200 {
		t.Fatal(c)
	}
	for i := 0; i < 10; i++ {
		do(h, "GET", "/api/cars", nil, "u", "wrong")
	}
	if c := do(h, "GET", "/api/cars", nil, "u", pw).Code; c != 429 {
		t.Fatal("not limited", c)
	}
	if _, err := newServer(t.TempDir(), "u", "short"); err == nil {
		t.Fatal("short password accepted")
	}
}
func TestFlow(t *testing.T) {
	_, h := mk(t)
	for id, lvl := range map[string]float64{"1700000000000": .04, "1700001000000": .16} {
		if w := do(h, "POST", "/api/upload", export(id, 10, lvl), "u", pw); w.Code != 200 {
			t.Fatal(w.Body)
		}
	}
	do(h, "POST", "/api/upload", export("1700000000000", 10, .04), "u", pw) // again: replaces
	w := do(h, "GET", "/api/average?car=hatchback&min_mph=25&max_mph=30", nil, "u", pw)
	var a struct {
		Drives  int
		Summary summary
	}
	json.Unmarshal(w.Body.Bytes(), &a)
	if a.Drives != 2 || a.Summary.PeakHz != 30 || a.Summary.SteadySeconds != 40 || a.Summary.BandRMS["25-35"] < .317 || a.Summary.BandRMS["25-35"] > .319 {
		t.Fatalf("%+v", a)
	}
	if b := do(h, "GET", "/api/drives?from=2023-11-14&to=2023-11-14", nil, "u", pw).Body.String(); strings.Count(b, `"id"`) != 2 {
		t.Fatal(b)
	}
	if b := do(h, "GET", "/api/drives?to=2023-11-13", nil, "u", pw).Body.String(); strings.Count(b, `"id"`) != 0 {
		t.Fatal(b)
	}
	if c := do(h, "GET", "/api/drives/1700000000000/raw.zip", nil, "u", pw).Code; c != 200 {
		t.Fatal(c)
	}
	if b := do(h, "GET", "/api/drives/1700000000000/track", nil, "u", pw).Body.String(); strings.Count(b, `"lat"`) != 2 || !strings.Contains(b, `"mph":22.3694`) || !strings.Contains(b, `"mph":null`) {
		t.Fatal(b)
	}
	for _, p := range []string{"/api/drives/abc/track", "/api/drives/..%2f..%2fetc/raw.zip", "/api/drives/abc", "/api/drives/9999999999999"} {
		if c := do(h, "GET", p, nil, "u", pw).Code; c != 404 {
			t.Fatal(p, c)
		}
	}
	if c := do(h, "POST", "/api/upload", []byte("junk"), "u", pw).Code; c != 400 {
		t.Fatal(c)
	}
	ex := do(h, "GET", "/api/export.zip", nil, "u", pw)
	zr, err := zip.NewReader(bytes.NewReader(ex.Body.Bytes()), int64(ex.Body.Len()))
	if err != nil || len(zr.File) != 3 || zr.File[0].Name != "buzzlab.db" {
		t.Fatal("export", err)
	}
	if c := do(h, "DELETE", "/api/drives/1700000000000", nil, "u", pw).Code; c != 204 {
		t.Fatal(c)
	}
	if c := do(h, "GET", "/api/drives/1700000000000", nil, "u", pw).Code; c != 404 {
		t.Fatal(c)
	}
}

func TestSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	for i, want := range []int{0, 1, 1} {
		s, err := newServer(dir, "u", pw)
		if err != nil {
			t.Fatal(err)
		}
		h := s.routes()
		if n := strings.Count(do(h, "GET", "/api/drives", nil, "u", pw).Body.String(), `"id"`); n != want {
			t.Fatal(i, n)
		}
		if i == 0 {
			do(h, "POST", "/api/upload", export("1700000000000", 10, .04), "u", pw)
		}
		s.db.Close()
	}
}

func TestOldShortStatsMix(t *testing.T) {
	a, b := empty(), empty()
	a.N[5], b.N[5] = 1, 1
	b.PSD[5] = b.PSD[5][:oldNF]
	a.PSD[5][30-f0], b.PSD[5][30-f0] = 4, 2
	if !a.valid() || !b.valid() {
		t.Fatal("valid")
	}
	for _, order := range [][]stats{{a, b}, {b, a}} {
		x := empty()
		add(&x, order[0])
		add(&x, order[1])
		o := summarise(x, 10, 80, true)
		if o.PeakHz != 30 || len(o.Spectrum) != oldNF || o.Spectrum["30"] != 1.7321 {
			t.Fatalf("%d %v", len(o.Spectrum), o.Spectrum["30"])
		}
	}
	if o := summarise(a, 10, 80, true); len(o.Spectrum) != nf || o.BandRMS["120-230"] != 0 {
		t.Fatal(len(o.Spectrum))
	}
}

func TestShares(t *testing.T) {
	s, h := mk(t)
	for _, id := range []string{"1700000000000", "1700001000000"} {
		do(h, "POST", "/api/upload", export(id, 10, .04), "u", pw)
	}
	mkShare := func(body string) (int, string) {
		w := do(h, "POST", "/api/shares", []byte(body), "u", pw)
		var r struct{ Path string }
		json.Unmarshal(w.Body.Bytes(), &r)
		return w.Code, r.Path
	}
	_, vp := mkShare(`{"title":"V","drives":["1700000000000"],"days":5,"view":{"car":"Hatchback","with":"Van","place":"","lo":30,"hi":50}}`)
	if b := do(h, "POST", vp+"/data", []byte(`{}`), "", "").Body.String(); !strings.Contains(b, `"view":{"car":"Hatchback","with":"Van","place":"","lo":30,"hi":50}`) {
		t.Fatal(b[:120])
	}
	if c, _ := mkShare(`{"title":"V","drives":["1700000000000"],"days":5,"view":{"lo":50,"hi":30}}`); c != 400 {
		t.Fatal("bad view", c)
	}
	s.db.Exec(`DELETE FROM shares`)
	if c := do(h, "POST", "/api/shares", []byte(`{}`), "", "").Code; c != 401 {
		t.Fatal("open create", c)
	}
	for _, bad := range []string{`{}`, `{"title":"x","drives":["9999999999999"],"days":5}`, `{"title":"x","drives":["1700000000000"],"days":999}`, `{"title":"x","drives":["1700000000000"],"days":5,"pin":"1"}`} {
		if c, _ := mkShare(bad); c != 400 {
			t.Fatal(bad, c)
		}
	}
	_, p := mkShare(`{"title":"Test","drives":["1700000000000"],"days":5}`)
	if w := do(h, "GET", p, nil, "", ""); w.Code != 200 || strings.Contains(w.Body.String(), "{{NONCE}}") || !strings.Contains(w.Header().Get("Content-Security-Policy"), "default-src 'none'") {
		t.Fatal(w.Code)
	}
	w := do(h, "POST", p+"/data", []byte(`{}`), "", "")
	b := w.Body.String()
	if w.Code != 200 || strings.Count(b, `"no":`) != 1 {
		t.Fatal(w.Code, b[:200])
	}
	for _, leak := range []string{"1700000000000", "lat", "Location", "raw", "note", "tyre"} {
		if strings.Contains(b, leak) {
			t.Fatal("leaks", leak)
		}
	}
	for _, x := range []string{"/api/drives", "/s/x/../../api/drives", "/s/" + strings.Repeat("a", 43)} {
		if c := do(h, "GET", x, nil, "", "").Code; c == 200 {
			t.Fatal(x, c)
		}
	}
	_, q := mkShare(`{"title":"Pin","drives":["1700000000000"],"days":5,"pin":"4821"}`)
	if c := do(h, "POST", q+"/data", []byte(`{}`), "", "").Code; c != 401 {
		t.Fatal(c)
	}
	if c := do(h, "POST", q+"/data", []byte(`{"pin":"4821"}`), "", "").Code; c != 200 {
		t.Fatal(c)
	}
	for i := 0; i < 10; i++ {
		do(h, "POST", q+"/data", []byte(`{"pin":"0000"}`), "", "")
	}
	if c := do(h, "POST", q+"/data", []byte(`{"pin":"4821"}`), "", "").Code; c != 429 {
		t.Fatal("pin not limited", c)
	}
	s.fails = map[string][]time.Time{}
	var l struct{ Shares []share }
	json.Unmarshal(do(h, "GET", "/api/shares", nil, "u", pw).Body.Bytes(), &l)
	if len(l.Shares) != 2 || !l.Shares[0].HasPin || l.Shares[1].Opens != 1 {
		t.Fatalf("%+v", l)
	}
	if c := do(h, "DELETE", "/api/shares/"+strconv.FormatInt(l.Shares[1].ID, 10), nil, "u", pw).Code; c != 204 {
		t.Fatal(c)
	}
	if c := do(h, "POST", p+"/data", []byte(`{}`), "", "").Code; c != 404 {
		t.Fatal("cancelled link works", c)
	}
	s.db.Exec(`UPDATE shares SET expires=1`)
	if c := do(h, "POST", q+"/data", []byte(`{"pin":"4821"}`), "", "").Code; c != 404 {
		t.Fatal("expired link works", c)
	}
}
