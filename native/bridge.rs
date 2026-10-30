//! Rattrapante's extensions to the pinned C API. All results use upstream owners.
//! Structured reads use private EDN, copied before the result is released. No
//! Rust layout or borrowed pointer is exposed by this encoding.
use crate::{
    byte_span::{to_str, AMbyteSpan},
    change::AMchange,
    doc::{
        utils::{to_doc, to_doc_mut},
        AMdoc,
    },
    items::AMitems,
    obj::{to_obj_id, AMobjId},
    result::{to_result, AMresult},
};
use am::{transaction::Transactable, ReadDoc};
use automerge as am;
use std::fmt::Write;

fn error(e: impl std::fmt::Display) -> *mut AMresult {
    AMresult::error(&e.to_string()).into()
}

/// The upstream single-change decoder validates operations and framing but does
/// not check the header checksum. Check it against the decoded change hash too.
#[no_mangle]
pub unsafe extern "C" fn AMrChangeFromBytes(src: *const u8, count: usize) -> *mut AMresult {
    let bytes = std::slice::from_raw_parts(src, count);
    match am::Change::from_bytes(bytes.to_vec()) {
        Ok(change) => {
            if bytes.get(4..8) != Some(&change.hash().0[..4]) {
                return error("Change checksum mismatch");
            }
            to_result(change)
        }
        Err(e) => error(e),
    }
}

fn optional_bytes(bytes: Option<&[u8]>) -> *mut AMresult {
    match bytes {
        Some(b) => to_result(b.to_vec()),
        None => to_result(None::<am::Change>),
    }
}

#[no_mangle]
pub unsafe extern "C" fn AMrAuthor(doc: *const AMdoc) -> *mut AMresult {
    optional_bytes(to_doc!(doc).get_author().map(|a| a.as_bytes()))
}

#[no_mangle]
pub unsafe extern "C" fn AMrSetAuthor(doc: *mut AMdoc, author: AMbyteSpan) -> *mut AMresult {
    let author = if author.src.is_null() {
        None
    } else {
        Some(am::Author::from(
            std::slice::from_raw_parts(author.src, author.count).to_vec(),
        ))
    };
    to_doc_mut!(doc).set_author(author);
    to_result(Ok::<(), am::AutomergeError>(()))
}

#[no_mangle]
pub unsafe extern "C" fn AMrChangeAuthor(change: *const AMchange) -> *mut AMresult {
    match change.as_ref() {
        Some(c) => optional_bytes(c.as_ref().author().as_ref().map(|a| a.as_bytes())),
        None => error("Invalid change"),
    }
}

fn edn(s: String) -> *mut AMresult {
    to_result(Ok::<String, am::AutomergeError>(s))
}

fn string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

fn scalar(out: &mut String, value: &am::ScalarValue) {
    use am::ScalarValue::*;
    match value {
        Null => out.push_str("nil"),
        Boolean(v) => {
            let _ = write!(out, "{v}");
        }
        Int(v) => {
            let _ = write!(out, "{v}");
        }
        Uint(v) => {
            let _ = write!(out, "#rattrapante/uint {v}N");
        }
        Counter(v) => {
            let _ = write!(out, "#rattrapante/counter {}", i64::from(v));
        }
        Timestamp(v) => {
            let _ = write!(out, "#rattrapante/timestamp {v}");
        }
        F64(v) => {
            if v.is_nan() {
                out.push_str("##NaN");
            } else if *v == f64::INFINITY {
                out.push_str("##Inf");
            } else if *v == f64::NEG_INFINITY {
                out.push_str("##-Inf");
            } else {
                let _ = write!(out, "{v:?}");
            }
        }
        Str(v) => string(out, v),
        Bytes(v) => {
            out.push_str("#rattrapante/bytes ");
            string(out, &hex::encode(v));
        }
        Unknown { type_code, bytes } => {
            let _ = write!(out, "#rattrapante/unknown [{type_code} ");
            string(out, &hex::encode(bytes));
            out.push(']');
        }
    }
}

fn marks(out: &mut String, value: Option<&am::marks::MarkSet>) {
    out.push('{');
    if let Some(value) = value {
        for (k, v) in value.iter() {
            string(out, k);
            out.push(' ');
            scalar(out, v);
            out.push(' ');
        }
    }
    out.push('}');
}

fn kind(t: am::ObjType) -> &'static str {
    match t {
        am::ObjType::Map => ":map",
        am::ObjType::List => ":list",
        am::ObjType::Text => ":text",
        am::ObjType::Table => ":table",
    }
}

fn value(out: &mut String, v: &am::Value<'_>, id: &am::ObjId) {
    match v {
        am::Value::Scalar(v) => scalar(out, v),
        am::Value::Object(t) => {
            out.push_str("{:object-id ");
            string(out, &id.to_string());
            out.push_str(" :object-type ");
            out.push_str(kind(*t));
            out.push('}');
        }
    }
}

fn prop(out: &mut String, p: &am::Prop) {
    match p {
        am::Prop::Map(k) => string(out, k),
        am::Prop::Seq(i) => {
            let _ = write!(out, "{i}");
        }
    }
}

fn patch(out: &mut String, p: &am::Patch) {
    use am::PatchAction::*;
    out.push_str("{:object-id ");
    string(out, &p.obj.to_string());
    out.push_str(" :path [");
    for (id, key) in &p.path {
        out.push('[');
        string(out, &id.to_string());
        out.push(' ');
        prop(out, key);
        out.push_str("] ");
    }
    out.push_str("] ");
    match &p.action {
        PutMap {
            key,
            value: (v, id),
            conflict,
        } => {
            out.push_str(":action :put-map :key ");
            string(out, key);
            out.push_str(" :value ");
            value(out, v, id);
            let _ = write!(out, " :conflict? {conflict}");
        }
        PutSeq {
            index,
            value: (v, id),
            conflict,
        } => {
            let _ = write!(out, ":action :put-seq :index {index} :value ");
            value(out, v, id);
            let _ = write!(out, " :conflict? {conflict}");
        }
        Insert { index, values } => {
            let _ = write!(out, ":action :insert :index {index} :values [");
            for (v, id, conflict) in values.iter() {
                out.push_str("{:value ");
                value(out, v, id);
                let _ = write!(out, " :conflict? {conflict}}} ");
            }
            out.push(']');
        }
        SpliceText {
            index,
            value,
            marks: m,
        } => {
            let _ = write!(out, ":action :splice-text :index {index} :value ");
            string(out, &value.make_string());
            out.push_str(" :marks ");
            marks(out, m.as_ref());
        }
        Increment { prop: key, value } => {
            out.push_str(":action :increment :key ");
            prop(out, key);
            let _ = write!(out, " :value {value}");
        }
        Conflict { prop: key } => {
            out.push_str(":action :conflict :key ");
            prop(out, key);
        }
        DeleteMap { key } => {
            out.push_str(":action :delete-map :key ");
            string(out, key);
        }
        DeleteSeq { index, length } => {
            let _ = write!(out, ":action :delete-seq :index {index} :length {length}");
        }
        Mark { marks } => {
            out.push_str(":action :mark :marks [");
            for m in marks {
                let _ = write!(out, "{{:start {} :end {} :name ", m.start, m.end);
                string(out, m.name());
                out.push_str(" :value ");
                scalar(out, m.value());
                out.push_str("} ");
            }
            out.push(']');
        }
    }
    out.push('}');
}

fn heads(items: *const AMitems) -> Result<Vec<am::ChangeHash>, String> {
    unsafe {
        match items.as_ref() {
            None => Ok(vec![]),
            Some(items) => Vec::<am::ChangeHash>::try_from(items).map_err(|e| e.to_string()),
        }
    }
}

fn checked_heads(doc: &mut AMdoc, items: *const AMitems) -> Result<Vec<am::ChangeHash>, String> {
    let h = heads(items)?;
    for hash in &h {
        if doc.get_change_by_hash(hash).is_none() {
            return Err(format!("Unknown history head {hash}"));
        }
    }
    Ok(h)
}

/// Validate Unicode scalar boundaries without copying the complete text.
#[no_mangle]
pub unsafe extern "C" fn AMrTextBoundary(
    doc: *const AMdoc,
    obj: *const AMobjId,
    index: usize,
) -> *mut AMresult {
    let doc = to_doc!(doc);
    let obj = to_obj_id!(obj);
    if index == doc.length(obj) {
        return to_result(Ok::<(), am::AutomergeError>(()));
    }
    let result = doc
        .get_cursor(obj, index, None)
        .and_then(|c| doc.get_cursor_position(obj, &c, None));
    match result {
        Ok(i) if i == index => to_result(Ok::<(), am::AutomergeError>(())),
        Ok(_) => error("Position splits a Unicode scalar"),
        Err(e) => error(e),
    }
}

#[no_mangle]
pub unsafe extern "C" fn AMrCursor(
    doc: *const AMdoc,
    obj: *const AMobjId,
    index: usize,
    endpoint: i64,
    before: bool,
) -> *mut AMresult {
    let position = match endpoint {
        -1 => am::CursorPosition::Start,
        0 => am::CursorPosition::Index(index),
        1 => am::CursorPosition::End,
        _ => return error("Invalid cursor endpoint"),
    };
    to_result(to_doc!(doc).get_cursor_moving(
        to_obj_id!(obj),
        position,
        None,
        if before {
            am::MoveCursor::Before
        } else {
            am::MoveCursor::After
        },
    ))
}

#[no_mangle]
pub unsafe extern "C" fn AMrResolveObject(doc: *const AMdoc, id: AMbyteSpan) -> *mut AMresult {
    let doc = to_doc!(doc);
    match doc
        .import_obj(to_str!(id))
        .and_then(|id| doc.object_type(&id).map(|t| (id, t)))
    {
        Ok((id, t)) => to_result((Ok(id), "", t)),
        Err(e) => error(e),
    }
}

#[no_mangle]
pub unsafe extern "C" fn AMrSplitBlock(
    doc: *mut AMdoc,
    obj: *const AMobjId,
    index: usize,
) -> *mut AMresult {
    to_result((
        to_doc_mut!(doc).split_block(to_obj_id!(obj), index),
        index,
        am::ObjType::Map,
    ))
}

#[no_mangle]
pub unsafe extern "C" fn AMrJoinBlock(
    doc: *mut AMdoc,
    obj: *const AMobjId,
    index: usize,
) -> *mut AMresult {
    to_result(to_doc_mut!(doc).join_block(to_obj_id!(obj), index))
}

#[no_mangle]
pub unsafe extern "C" fn AMrReplaceBlock(
    doc: *mut AMdoc,
    obj: *const AMobjId,
    index: usize,
) -> *mut AMresult {
    to_result((
        to_doc_mut!(doc).replace_block(to_obj_id!(obj), index),
        index,
        am::ObjType::Map,
    ))
}

#[no_mangle]
pub unsafe extern "C" fn AMrMarksAt(
    doc: *const AMdoc,
    obj: *const AMobjId,
    index: usize,
) -> *mut AMresult {
    match to_doc!(doc).get_marks(to_obj_id!(obj), index, None) {
        Ok(m) => {
            let mut out = String::new();
            marks(&mut out, Some(&m));
            edn(out)
        }
        Err(e) => error(e),
    }
}

#[no_mangle]
pub unsafe extern "C" fn AMrBlock(
    doc: *const AMdoc,
    obj: *const AMobjId,
    index: usize,
) -> *mut AMresult {
    let result = to_doc!(doc)
        .get(to_obj_id!(obj), index)
        .map(|v| v.filter(|(v, _)| matches!(v, am::Value::Object(am::ObjType::Map))));
    to_result((result, index))
}

#[no_mangle]
pub unsafe extern "C" fn AMrUpdateText(
    doc: *mut AMdoc,
    obj: *const AMobjId,
    text: AMbyteSpan,
) -> *mut AMresult {
    to_result(to_doc_mut!(doc).update_text(to_obj_id!(obj), to_str!(text)))
}

#[no_mangle]
pub unsafe extern "C" fn AMrSpans(doc: *const AMdoc, obj: *const AMobjId) -> *mut AMresult {
    let doc = to_doc!(doc);
    let obj = to_obj_id!(obj);
    let spans = match doc.spans(obj) {
        Ok(v) => v,
        Err(e) => return error(e),
    };
    let mut out = String::from("[");
    let mut index = 0;
    for span in spans {
        match span {
            am::iter::Span::Text { text, marks: m } => {
                let _ = write!(out, "{{:type :text :index {index} :text ");
                string(&mut out, &text);
                out.push_str(" :marks ");
                marks(&mut out, m.as_deref());
                out.push_str("} ");
                index += text.encode_utf16().count();
            }
            am::iter::Span::Block(_) => {
                let id = match doc.get(obj, index) {
                    Ok(Some((_, id))) => id,
                    _ => return error("Block has no object ID"),
                };
                let _ = write!(out, "{{:type :block :index {index} :object-id ");
                string(&mut out, &id.to_string());
                out.push_str("} ");
                index += 1;
            }
        }
    }
    out.push(']');
    edn(out)
}

/// Explicit history comparison; no hidden incremental diff cursor.
#[no_mangle]
pub unsafe extern "C" fn AMrDiff(
    doc: *mut AMdoc,
    before: *const AMitems,
    after: *const AMitems,
) -> *mut AMresult {
    let doc = to_doc_mut!(doc);
    let before = match checked_heads(doc, before) {
        Ok(v) => v,
        Err(e) => return error(e),
    };
    let after = match checked_heads(doc, after) {
        Ok(v) => v,
        Err(e) => return error(e),
    };
    let mut out = String::from("[");
    for p in doc.diff(&before, &after) {
        patch(&mut out, &p);
        out.push(' ');
    }
    out.push(']');
    edn(out)
}
