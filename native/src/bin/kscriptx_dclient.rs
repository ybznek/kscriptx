//! Local-socket client for the kscriptx daemon.
//!
//! Protocol (big-endian), shared by Unix domain sockets and TCP loopback:
//!   str: i32 length + UTF-8 bytes
//!   request: cwd:str, argc:i32, args:str*
//!   response: u8 'O'|'E'|'X'; O/E → i32 len + bytes; X → i32 exitCode
//!
//! Usage:
//!   kscriptx-dclient --unix <sock-path> [args...]
//!   kscriptx-dclient --tcp <port> [args...]
//!   kscriptx-dclient <port> [args...]          # legacy TCP
//!
//! Exit 99 if the daemon is unreachable (launcher falls back to JVM).

use std::env;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::process::ExitCode;
use std::time::Duration;

#[cfg(unix)]
use std::os::unix::net::UnixStream;
#[cfg(unix)]
use std::path::Path;

enum Stream {
    Tcp(TcpStream),
    #[cfg(unix)]
    Unix(UnixStream),
}

impl Read for Stream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Stream::Tcp(s) => s.read(buf),
            #[cfg(unix)]
            Stream::Unix(s) => s.read(buf),
        }
    }
}

impl Write for Stream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        match self {
            Stream::Tcp(s) => s.write(buf),
            #[cfg(unix)]
            Stream::Unix(s) => s.write(buf),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Stream::Tcp(s) => s.flush(),
            #[cfg(unix)]
            Stream::Unix(s) => s.flush(),
        }
    }
}

fn write_i32(w: &mut impl Write, v: i32) -> std::io::Result<()> {
    w.write_all(&v.to_be_bytes())
}

fn read_i32(r: &mut impl Read) -> std::io::Result<i32> {
    let mut buf = [0u8; 4];
    r.read_exact(&mut buf)?;
    Ok(i32::from_be_bytes(buf))
}

fn write_str(w: &mut impl Write, s: &str) -> std::io::Result<()> {
    let bytes = s.as_bytes();
    write_i32(w, bytes.len() as i32)?;
    w.write_all(bytes)
}

fn connect(args: &mut impl Iterator<Item = String>) -> Result<(Stream, Vec<String>), u8> {
    let Some(first) = args.next() else {
        eprintln!("usage: kscriptx-dclient (--unix <path>|--tcp <port>|<port>) [args...]");
        return Err(1);
    };

    match first.as_str() {
        "--unix" | "-u" => {
            #[cfg(unix)]
            {
                let Some(path) = args.next() else {
                    return Err(1);
                };
                if !Path::new(&path).exists() {
                    return Err(99);
                }
                return match UnixStream::connect(&path) {
                    Ok(s) => {
                        let _ = s.set_read_timeout(None);
                        let _ = s.set_write_timeout(None);
                        Ok((Stream::Unix(s), args.collect()))
                    }
                    Err(_) => Err(99),
                };
            }
            #[cfg(not(unix))]
            {
                let _ = args.next();
                eprintln!("kscriptx-dclient: --unix is not supported on this platform");
                Err(1)
            }
        }
        "--tcp" | "-t" => {
            let Some(port_s) = args.next() else {
                return Err(1);
            };
            connect_tcp(&port_s, args.collect())
        }
        other => connect_tcp(other, args.collect()),
    }
}

fn connect_tcp(port_s: &str, rest: Vec<String>) -> Result<(Stream, Vec<String>), u8> {
    let Ok(port) = port_s.parse::<u16>() else {
        return Err(1);
    };
    match TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_millis(200)) {
        Ok(s) => {
            let _ = s.set_read_timeout(None);
            let _ = s.set_write_timeout(None);
            Ok((Stream::Tcp(s), rest))
        }
        Err(_) => Err(99),
    }
}

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let (mut stream, script_args) = match connect(&mut args) {
        Ok(v) => v,
        Err(code) => return ExitCode::from(code),
    };

    let cwd = env::current_dir()
        .ok()
        .and_then(|p| p.into_os_string().into_string().ok())
        .unwrap_or_default();

    if write_str(&mut stream, &cwd).is_err()
        || write_i32(&mut stream, script_args.len() as i32).is_err()
    {
        return ExitCode::from(1);
    }
    for a in &script_args {
        if write_str(&mut stream, a).is_err() {
            return ExitCode::from(1);
        }
    }
    if stream.flush().is_err() {
        return ExitCode::from(1);
    }

    loop {
        let mut kind = [0u8; 1];
        if stream.read_exact(&mut kind).is_err() {
            return ExitCode::from(1);
        }
        match kind[0] {
            b'O' | b'E' => {
                let Ok(n) = read_i32(&mut stream) else {
                    return ExitCode::from(1);
                };
                if n < 0 {
                    return ExitCode::from(1);
                }
                let mut buf = vec![0u8; n as usize];
                if n > 0 && stream.read_exact(&mut buf).is_err() {
                    return ExitCode::from(1);
                }
                let out: &mut dyn Write = if kind[0] == b'O' {
                    &mut std::io::stdout()
                } else {
                    &mut std::io::stderr()
                };
                let _ = out.write_all(&buf);
                let _ = out.flush();
            }
            b'X' => {
                let Ok(code) = read_i32(&mut stream) else {
                    return ExitCode::from(1);
                };
                let code = if (0..=255).contains(&code) {
                    code as u8
                } else if code == 0 {
                    0
                } else {
                    1
                };
                return ExitCode::from(code);
            }
            _ => return ExitCode::from(1),
        }
    }
}
