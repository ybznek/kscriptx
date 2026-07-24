//! Parse JaCoCo/Kover XML and print a markdown coverage summary.
//! Usage: kscriptx-coverage <report.xml>

use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::process::ExitCode;

#[derive(Clone, Copy, Default)]
struct Counter {
    covered: i64,
    missed: i64,
}

impl Counter {
    fn total(self) -> i64 {
        self.covered + self.missed
    }
    fn pct(self) -> f64 {
        let t = self.total();
        if t == 0 {
            0.0
        } else {
            100.0 * self.covered as f64 / t as f64
        }
    }
}

fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let key = format!("{name}=\"");
    let start = tag.find(&key)? + key.len();
    let end = tag[start..].find('"')? + start;
    Some(&tag[start..end])
}

fn collect_counters(xml: &str, map: &mut BTreeMap<String, Counter>) {
    let mut rest = xml;
    while let Some(idx) = rest.find("<counter") {
        let after = &rest[idx..];
        let end = after.find('>').unwrap_or(after.len());
        let tag = &after[..end];
        if let (Some(ty), Some(missed), Some(covered)) = (
            attr(tag, "type"),
            attr(tag, "missed").and_then(|s| s.parse::<i64>().ok()),
            attr(tag, "covered").and_then(|s| s.parse::<i64>().ok()),
        ) {
            let e = map.entry(ty.to_string()).or_default();
            e.missed += missed;
            e.covered += covered;
        }
        rest = if end + 1 < after.len() {
            &after[end + 1..]
        } else {
            ""
        };
    }
}

/// Report-level counters sit after the last `</package>` (JaCoCo layout).
fn report_level_counters(xml: &str) -> BTreeMap<String, Counter> {
    let mut map = BTreeMap::new();
    let start = xml
        .rfind("</package>")
        .map(|i| i + "</package>".len())
        .unwrap_or(0);
    let end = xml.find("</report>").unwrap_or(xml.len());
    if start < end {
        collect_counters(&xml[start..end], &mut map);
    }
    map
}

/// Sum package-level counters (after nested class/sourcefile sections).
fn package_level_counters(xml: &str) -> BTreeMap<String, Counter> {
    let mut map = BTreeMap::new();
    let mut rest = xml;
    while let Some(pstart) = rest.find("<package") {
        let after_open = &rest[pstart..];
        let Some(close_rel) = after_open.find("</package>") else {
            break;
        };
        let package = &after_open[..close_rel];
        let suffix_from = package
            .rfind("</class>")
            .or_else(|| package.rfind("</sourcefile>"))
            .map(|i| {
                let end_tag = if package[i..].starts_with("</class>") {
                    i + "</class>".len()
                } else {
                    i + "</sourcefile>".len()
                };
                end_tag
            })
            .unwrap_or(0);
        if suffix_from < package.len() {
            collect_counters(&package[suffix_from..], &mut map);
        }
        rest = &after_open[close_rel + "</package>".len()..];
    }
    map
}

fn parse_counters(xml: &str) -> BTreeMap<String, Counter> {
    let report = report_level_counters(xml);
    if !report.is_empty() {
        report
    } else {
        package_level_counters(xml)
    }
}

fn main() -> ExitCode {
    let path = match env::args().nth(1) {
        Some(p) => p,
        None => {
            eprintln!("usage: kscriptx-coverage <jacoco-or-kover-xml>");
            return ExitCode::from(1);
        }
    };
    let Ok(xml) = fs::read_to_string(&path) else {
        println!("### Coverage\n\n_No report at `{path}`._\n");
        return ExitCode::SUCCESS;
    };

    let totals = parse_counters(&xml);
    if totals.is_empty() {
        println!("### Coverage\n\n_No counters in `{path}`._\n");
        return ExitCode::SUCCESS;
    }

    println!("### Coverage\n");
    println!("| Type | Covered | Missed | Total | % |");
    println!("|---|---:|---:|---:|---:|");
    for (ty, c) in totals {
        println!(
            "| {ty} | {} | {} | {} | {:.1} |",
            c.covered,
            c.missed,
            c.total(),
            c.pct()
        );
    }
    println!();
    ExitCode::SUCCESS
}
