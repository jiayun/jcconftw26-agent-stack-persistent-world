import { useEffect, useRef, useState } from "react";
import { api, chapters, type Save } from "./api";

type Props = {
  saves: Save[];
  currentId: string;
  busy: boolean;
  onBusy: (busy: boolean) => void;
  onRefresh: () => Promise<Save[]>;
  onDeleted: (ids: string[]) => Promise<void>;
  onError: (error: unknown) => void;
};

export function SaveManager({ saves, currentId, busy, onBusy, onRefresh, onDeleted, onError }: Props) {
  const [selected, setSelected] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [confirmation, setConfirmation] = useState<Save[]>([]);
  const [deleting, setDeleting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const locked = busy || deleting || exporting;
  const visible = saves.filter((save) => save.name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  const selectable = visible.filter((save) => !save.busy);
  const allSelected = selectable.length > 0 && selectable.every((save) => selected.includes(save.id));
  useEffect(() => {
    setSelected((ids) => ids.filter((id) => saves.some((save) => save.id === id && !save.busy)));
  }, [saves]);
  useEffect(() => {
    if (confirmation.length) dialog.current?.showModal();
    else dialog.current?.close();
  }, [confirmation]);

  async function exportOne(save: Save) {
    setExporting(true);
    try {
      const data = await api<unknown>(`/saves/${save.id}/export`);
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = `spring-festival-${save.id}.json`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { onError(error); }
    finally { setExporting(false); }
  }
  async function remove() {
    if (locked || !confirmation.length) return;
    setDeleting(true);
    onBusy(true);
    try {
      const result = await api<{ deletedIds: string[] }>("/saves/delete", "POST", {
        saves: confirmation.map((save) => ({ id: save.id, expectedRevision: save.revision })),
      });
      setConfirmation([]);
      setSelected([]);
      await onDeleted(result.deletedIds);
    } catch (error) {
      setConfirmation([]);
      onError(error);
      await onRefresh().catch(onError);
    } finally {
      setDeleting(false);
      onBusy(false);
    }
  }
  return (
    <article className="save-manager">
      <h2>存檔管理</h2>
      <p className="muted">共 {saves.length} 個存檔。勾選要刪除的故事，也可以先逐一匯出備份。</p>
      <label htmlFor="save-search">搜尋存檔名稱
        <input id="save-search" type="search" value={search} disabled={locked} onChange={(event) => setSearch(event.target.value)} placeholder="輸入名稱" />
      </label>
      <div className="actions">
        <button disabled={locked} onClick={() => onRefresh().catch(onError)}>重新整理清單</button>
        <button disabled={locked || !selectable.length} onClick={() => setSelected((ids) => allSelected
          ? ids.filter((id) => !selectable.some((save) => save.id === id))
          : [...new Set([...ids, ...selectable.map((save) => save.id)])])}>
          {allSelected ? "取消勾選顯示項目" : "勾選顯示項目"}
        </button>
        <button disabled={locked || !selected.length} onClick={() => setSelected([])}>清除勾選</button>
      </div>
      <ul className="save-list" aria-label="全部存檔">
        {visible.map((save) => (
          <li key={save.id}>
            <label>
              <input type="checkbox" checked={selected.includes(save.id)} disabled={locked || save.busy}
                onChange={(event) => setSelected((ids) => event.target.checked ? [...ids, save.id] : ids.filter((id) => id !== save.id))} />
              <span className="save-details">
                <strong>{save.name}{save.id === currentId && <small className="save-badge">目前使用</small>}</strong>
                <span>第 {save.day} 天 · {chapters[save.chapter] || "故事進行中"} · {save.placeName}</span>
                <small>{save.ending === "departed" ? "已抵達北方" : save.ending === "postponed" ? "已約好延期" : "故事進行中"}{save.busy ? " · 回合處理中，暫不可刪除" : ""} · {save.id.slice(0, 8)}</small>
              </span>
            </label>
            <button disabled={locked} aria-label={`匯出 ${save.name}（${save.id.slice(0, 8)}）`} onClick={() => exportOne(save)}>匯出</button>
          </li>
        ))}
        {!visible.length && <li className="muted">沒有符合名稱的存檔。</li>}
      </ul>
      <div className="actions">
        <button className="danger" disabled={locked || !selected.length} onClick={() => setConfirmation(saves.filter((save) => selected.includes(save.id)))}>
          刪除已選的 {selected.length} 個存檔
        </button>
      </div>
      <dialog ref={dialog} className="delete-dialog" aria-labelledby="delete-save-title"
        onCancel={(event) => { event.preventDefault(); if (!locked) setConfirmation([]); }}>
        <h2 id="delete-save-title">刪除這 {confirmation.length} 個存檔？</h2>
        <p>故事進度、記憶、回合紀錄與連線權杖都會永久刪除。匯出的備份可重新匯入故事進度。</p>
        <ul>{confirmation.map((save) => <li key={save.id}>{save.name} · 第 {save.day} 天 · {save.id.slice(0, 8)}{save.id === currentId ? "（目前使用）" : ""}</li>)}</ul>
        {confirmation.some((save) => save.id === currentId) && <p>{confirmation.length === saves.length ? "刪除後將回到故事開始畫面。" : "刪除後將切換至另一個存檔。"}</p>}
        <div className="actions">
          <button autoFocus disabled={locked} onClick={() => setConfirmation([])}>取消</button>
          <button className="danger" disabled={locked} onClick={remove}>{deleting ? "刪除中…" : "確認永久刪除"}</button>
        </div>
      </dialog>
    </article>
  );
}
