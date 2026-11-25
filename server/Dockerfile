FROM golang:1.27-alpine AS build
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /buzz .
FROM scratch
COPY --from=build /buzz /buzz
USER 1001:1001
ENV BUZZ_ADDR=:8095 BUZZ_DATA=/data TMPDIR=/data
ENTRYPOINT ["/buzz"]
