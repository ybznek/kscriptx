//! Loopback TCP client for the kscriptx daemon.
//!
//! Protocol (big-endian):
//!   str: i32 length + UTF-8 bytes
//!   request: cwd:str, argc:i32, args:str*
//!   response: u8 'O'|'E'|'X'; O/E → i32 len + bytes; X → i32 exitCode
//!
//! Usage: kscriptx-dclient <port> [args...]
//! Exit 99 if the daemon is unreachable (launcher falls back to JVM).

use std::env;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::process::ExitCode;
use std::time::Duration;

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

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let Some(port_s) = args.next() else {
        eprintln!("usage: kscriptx-dclient <port> [args...]");
        return ExitCode::from(1);
    };
    let Ok(port) = port_s.parse::<u16>() else {
        return ExitCode::from(1);
    };

    let mut stream = match TcpStream::connect_timeout(
        &([127, 0, 0, 1], port).into(),
        Duration::from_millis(200),
    ) {
        Ok(s) => s,
        Err(_) => return ExitCode::from(99),
    };
    let _ = stream.set_read_timeout(None);
    let _ = stream.set_write_timeout(None);

    let cwd = env::current_dir()
        .ok()
        .and_then(|p| p.into_os_string().into_string().ok())
        .unwrap_or_default();

    let script_args: Vec<String> = args.collect();
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
