package main

import (
	"io"
	"log"
	"net"
	"sync"
)

// tcpProxy exposes one tailnet TCP port to a loopback HTTP port.
// It is used for the PBX panel / client-voice API over Tailscale.
type tcpProxy struct {
	mu       sync.Mutex
	listener net.Listener
	ip       string
	target   string
}

func (p *tcpProxy) Open(listen func(network, addr string) (net.Listener, error), ip string, port int, target string) {
	p.Close()
	ln, err := listen("tcp", net.JoinHostPort(ip, itoa(port)))
	if err != nil {
		log.Printf("http relay %d: %v", port, err)
		return
	}
	p.mu.Lock()
	p.listener, p.ip, p.target = ln, ip, target
	p.mu.Unlock()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go p.handle(c)
		}
	}()
}

func (p *tcpProxy) handle(c net.Conn) {
	defer c.Close()
	up, err := net.Dial("tcp", p.target)
	if err != nil {
		return
	}
	defer up.Close()
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); _, _ = io.Copy(up, c) }()
	go func() { defer wg.Done(); _, _ = io.Copy(c, up) }()
	wg.Wait()
}

func (p *tcpProxy) Close() {
	p.mu.Lock()
	ln := p.listener
	p.listener, p.ip = nil, ""
	p.mu.Unlock()
	if ln != nil {
		_ = ln.Close()
	}
}

func (p *tcpProxy) IP() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.ip
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [6]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
