import { StrictMode, useState, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import requirements from "../../docs/product/requirements.md?raw";
import architecture from "../../docs/product/architecture.md?raw";
import milestones from "../../docs/product/milestones.md?raw";
import operations from "../../docs/product/operations.md?raw";
import validation from "../../docs/validation.md?raw";
import { defaultFeatures, loadFeatures, saveFeatures, type Features } from "./features";
import "./styles.css";
import "./review.css";
const documents = { Requirements: requirements, Architecture: architecture, Milestones: milestones, Operations: operations, Validation: validation };
type DocumentName = keyof typeof documents;
// Deliberately supports only repository Markdown. Raw HTML is always rendered as text.
function Markdown({ source }: { source: string }) {
  const lines = source.split("\n");
  const blocks: ReactNode[] = [];
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index] ?? "";
    if (!line.trim()) continue;
    if (line.startsWith("|")) {
      const rows: string[][] = [];
      while (lines[index]?.startsWith("|")) {
        const cells = lines[index]!.split("|").slice(1, -1).map((cell) => cell.trim());
        if (!cells.every((cell) => /^:?-+:?$/.test(cell))) rows.push(cells);
        index++;
      }
      index--;
      blocks.push(<div className="review-table" key={index}><table><thead><tr>{rows[0]?.map((cell, n) => <th key={n} scope="col">{cell}</th>)}</tr></thead><tbody>{rows.slice(1).map((cells, row) => <tr key={row}>{cells.map((cell, col) => <td key={col}>{cell}</td>)}</tr>)}</tbody></table></div>);
    } else if (line.startsWith("### ")) blocks.push(<h3 key={index}>{line.slice(4)}</h3>);
    else if (line.startsWith("## ")) blocks.push(<h2 key={index}>{line.slice(3)}</h2>);
    else if (line.startsWith("# ")) blocks.push(<h1 key={index}>{line.slice(2)}</h1>);
    else if (line.startsWith("- ")) {
      const items: string[] = [];
      while (lines[index]?.startsWith("- ")) { items.push(lines[index]!.slice(2)); index++; }
      index--;
      blocks.push(<ul key={index}>{items.map((item, n) => <li key={n}>{item.replace(/^\[x\] /, "✓ ").replace(/^\[ \] /, "○ ")}</li>)}</ul>);
    } else blocks.push(<p key={index}>{line}</p>);
  }
  return <>{blocks}</>;
}
function Review() {
  const initial = location.hash.slice(1);
  const [selected, setSelected] = useState<DocumentName | "Controls">(Object.hasOwn(documents, initial) ? initial as DocumentName : initial === "Controls" ? "Controls" : "Requirements");
  const [features, setFeatures] = useState(loadFeatures);
  const [notice, setNotice] = useState<string | null>(null);
  function change(next: Features) { setFeatures(next); setNotice(saveFeatures(next) ?? "Saved for this tab. Open the viewer to apply."); }
  return <div className="app-shell review-shell">
    <header className="topbar"><a className="brand" href="./index.html">lantern</a><span className="privacy-label">PRODUCT REVIEW · UNLISTED</span><a href="./index.html">Open viewer</a></header>
    <main>
      <section className="review-intro"><p className="eyebrow">BUILDING THE COMPLETE CAMERA NETWORK</p><h1>From spare phone to peace of mind.</h1><p className="muted">The product contract, architecture and delivery ledger. These documents ship from the repository with every build.</p><div className="review-facts"><span>Standard WebRTC · implemented</span><span>Local evidence · evolving</span><span>Peer backup & CV · planned</span></div></section>
      <p className="review-scope">This URL is unlisted, not access-controlled. Controls here affect this browser only. Phone permissions, recording and peer trust remain owner-controlled.</p>
      <nav className="review-nav" aria-label="Review documents">{[...Object.keys(documents), "Controls"].map((name) => <a key={name} href={`#${name}`} aria-current={selected === name ? "page" : undefined} onClick={() => setSelected(name as typeof selected)}>{name}</a>)}</nav>
      <article className="review-document" aria-label={selected}>{selected === "Controls" ? <>
        <h1>Feature controls</h1><p>Session preferences for this browser tab. These do not change any phone's settings or activate unfinished capabilities.</p>
        <div className="review-control"><label><input type="checkbox" checked={features.doorbellAlerts} onChange={(event) => change({ ...features, doorbellAlerts: event.target.checked })} /> Foreground doorbell alerts</label><p>Poll compatible paired cameras while the viewer is visible. Answer explicitly; your microphone stays off until Talk.</p></div>
        <div className="review-control"><label><input type="checkbox" checked={features.transportDetails} onChange={(event) => change({ ...features, transportDetails: event.target.checked })} /> Connection details</label><p>Show standard WebRTC and camera capability information in the viewer.</p></div>
        {["Continuous MP4 recording — M2", "Encrypted peer backups — M3", "Person and presence analytics — M4", "Familiar faces and activity rules — M5"].map((label) => <div className="review-control" key={label}><label><input type="checkbox" disabled /> {label}</label><p>Planned. No runtime implementation is enabled by this page.</p></div>)}
        <button onClick={() => change({ ...defaultFeatures })}>Reset browser preferences</button>{notice && <p role="status">{notice}</p>}
      </> : <Markdown source={documents[selected]} />}</article>
    </main><footer className="page-footer"><span>Lantern / living product specification</span><span>Update docs, capabilities and tests in the same commit.</span></footer>
  </div>;
}
const root = document.getElementById("root");
if (!root) throw new Error("Missing review root");
createRoot(root).render(<StrictMode><Review /></StrictMode>);
