#!/usr/bin/env python3
"""
CameraGun AI Web Portal - Local HTTP Server
Serves the web calibrator and arcade simulator at http://localhost:8080
"""
import http.server
import socketserver
import webbrowser
import os
import sys

PORT = 8080
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def end_headers(self):
        # Enable caching headers and CORS for local testing
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store, must-revalidate')
        super().end_headers()

def run_server():
    os.chdir(DIRECTORY)
    with socketserver.TCPServer(("", PORT), Handler) as httpd:
        url = f"http://localhost:{PORT}/index.html"
        print("=" * 60)
        print("⚡ CAMERAGUN AI - CYBERPUNK WEB PORTAL & CALIBRATOR")
        print(f"📡 Server aktif di: {url}")
        print("Tekan Ctrl+C untuk menghentikan server.")
        print("=" * 60)
        
        # Try to open in default browser automatically
        try:
            webbrowser.open(url)
        except Exception:
            pass

        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nServer dihentikan.")
            sys.exit(0)

if __name__ == '__main__':
    run_server()
