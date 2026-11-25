package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
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
