import {useEffect, useRef, useState} from "react";
import "./style.css";
import {SaveManager} from "./SaveManager";
import {Landscape, Portrait} from "./SceneArt";
import {api, chapters, type Journal, type Memory, places, type Save, type Scene, type Turn,} from "./api";

export default function App() {
    const [saves, setSaves] = useState<Save[]>([]),
        [scene, setScene] = useState<Scene | null>(null),
        [tab, setTab] = useState("場景"),
        [error, setError] = useState(""),
        [busy, setBusy] = useState(false),
        [text, setText] = useState(""),
        [narrative, setNarrative] = useState(""),
        [memories, setMemories] = useState<Memory[]>([]),
        [journal, setJournal] = useState<Journal[]>([]),
        [settings, setSettings] = useState<{
            mode: string;
            mcpEnabled: boolean;
            debugEnabled: boolean;
            privacy: string;
        } | null>(null),
        [token, setToken] = useState(""),
        [connections, setConnections] = useState<
            { id: string; revoked: boolean }[]
        >([]),
        [saveName, setSaveName] = useState("我的河畔日常"),
        [trace, setTrace] = useState<unknown>(null),
        [lastTurn, setLastTurn] = useState(""),
        [editing, setEditing] = useState<string | null>(null),
        [editText, setEditText] = useState("");
    useEffect(() => {
        window.scrollTo({top: 0});
    }, [tab, scene?.saveId]);
    const generation = useRef(0),
        fileInput = useRef<HTMLInputElement>(null);
    const base = scene ? `/saves/${scene.saveId}` : "";
    const refreshSaves = () => api<Save[]>("/saves").then((list) => {
        setSaves(list);
        return list;
    });
    const report = (e: unknown) =>
        setError(e instanceof Error ? e.message : "發生未預期的錯誤，請重試。");

    async function load(id: string) {
        const current = ++generation.current;
        setError("");
        setToken("");
        setTrace(null);
        setNarrative("");
        setLastTurn("");
        setText("");
        setMemories([]);
        setJournal([]);
        setConnections([]);
        setEditing(null);
        const next = await api<Scene>(`/saves/${id}/scene`);
        if (current !== generation.current) return;
        setScene(next);
        localStorage.setItem("spring-save", id);
        const pending = localStorage.getItem("spring-pending:" + id);
        if (pending) {
            setBusy(true);
            await poll(id, pending, current);
        }
    }

    useEffect(() => {
        refreshSaves()
            .then(async (list) => {
                const stored = localStorage.getItem("spring-save");
                const id = list.some((save) => save.id === stored) ? stored : list[0]?.id;
                if (id) await load(id);
                else localStorage.removeItem("spring-save");
            })
            .catch(report);
        api<typeof settings>("/settings").then(setSettings).catch(report);
    }, []);
    useEffect(() => {
        if (!scene) return;
        let active = true;
        api<{ turn: Turn | null }>(base + "/turns/latest")
            .then((x) => {
                if (active && x.turn?.result) {
                    setNarrative(x.turn.result.narrative);
                    setLastTurn(x.turn.id);
                }
            })
            .catch(report);
        api<Memory[]>(base + "/memories")
            .then((x) => {
                if (active) setMemories(x);
            })
            .catch(report);
        api<{ items: Journal[] }>(base + "/journal")
            .then((x) => {
                if (active) setJournal(x.items);
            })
            .catch(report);
        api<typeof connections>(base + "/connections")
            .then((x) => {
                if (active) setConnections(x);
            })
            .catch(report);
        return () => {
            active = false;
        };
    }, [scene?.saveId, scene?.revision]);
    useEffect(() => {
        if (!scene || busy) return;
        const id = scene.saveId;
        const activeGeneration = generation.current;
        const timer = setInterval(() => {
            api<{ turn: Turn | null }>(`/saves/${id}/turns/latest`).then(({turn}) => {
                if (generation.current === activeGeneration && turn?.result) {
                    setNarrative(turn.result.narrative);
                    setLastTurn(turn.id);
                }
            }).catch(() => {
            });
            api<Scene>(`/saves/${id}/scene`)
                .then((s) =>
                    setScene((old) =>
                        old?.saveId === id && old.revision !== s.revision ? s : old,
                    ),
                )
                .catch(() => {
                });
        }, 3000);
        return () => clearInterval(timer);
    }, [scene?.saveId, busy]);

    async function afterDelete(ids: string[]) {
        for (const id of ids) {
            localStorage.removeItem("spring-pending:" + id);
            localStorage.removeItem("spring-request:" + id);
        }
        const currentDeleted = scene != null && ids.includes(scene.saveId);
        if (currentDeleted) {
            ++generation.current;
            localStorage.removeItem("spring-save");
            setScene(null);
            setNarrative("");
            setMemories([]);
            setJournal([]);
            setConnections([]);
            setToken("");
            setTrace(null);
            setLastTurn("");
            setText("");
            setEditing(null);
        }
        const remaining = await refreshSaves();
        if (currentDeleted) {
            if (remaining.length) await load(remaining[0].id);
            else setTab("場景");
        }
        setError("");
    }

    async function create(demo: boolean) {
        setBusy(true);
        setError("");
        try {
            const s = await api<Scene>("/saves", "POST", {
                name: demo ? "春祭前夕 · 展示存檔" : saveName,
                demo,
            });
            await refreshSaves();
            await load(s.saveId);
            setTab("場景");
        } catch (e) {
            report(e);
        } finally {
            setBusy(false);
        }
    }

    async function poll(id: string, turnId: string, current: number) {
        try {
            for (let i = 0; i < 100; i++) {
                if (current !== generation.current) return;
                const t = await api<Turn>(`/saves/${id}/turns/${turnId}`);
                if (t.status === "COMPLETE" && t.result) {
                    localStorage.removeItem("spring-pending:" + id);
                    setScene(t.result.scene);
                    setNarrative(t.result.narrative);
                    setLastTurn(turnId);
                    await refreshSaves();
                    return;
                }
                if (t.status === "FAILED") throw new Error(t.error || "回合未完成。");
                await new Promise((r) => setTimeout(r, 600));
            }
            throw new Error("回合仍在處理。重新載入可查詢原回合，請勿重送不同請求。");
        } finally {
            if (current === generation.current) setBusy(false);
        }
    }

    async function submit(suggestionId?: string) {
        if (!scene || busy) return;
        setBusy(true);
        setError("");
        setTrace(null);
        try {
            const payload = {
                requestId: crypto.randomUUID(),
                expectedRevision: scene.revision,
                ...(suggestionId ? {suggestionId} : {text}),
            };
            localStorage.setItem(
                "spring-request:" + scene.saveId,
                JSON.stringify(payload),
            );
            const receipt = await api<{ turnId: string }>(
                base + "/turns",
                "POST",
                payload,
            );
            localStorage.removeItem("spring-request:" + scene.saveId);
            localStorage.setItem("spring-pending:" + scene.saveId, receipt.turnId);
            setText("");
            await poll(scene.saveId, receipt.turnId, generation.current);
        } catch (e) {
            report(e);
            setBusy(false);
            api<Scene>(base + "/scene")
                .then(setScene)
                .catch(report);
        }
    }

    async function retry() {
        if (!scene) return;
        const raw = localStorage.getItem("spring-request:" + scene.saveId);
        if (!raw) {
            await load(scene.saveId);
            return;
        }
        setBusy(true);
        try {
            const r = await api<{ turnId: string }>(
                base + "/turns",
                "POST",
                JSON.parse(raw),
            );
            localStorage.removeItem("spring-request:" + scene.saveId);
            localStorage.setItem("spring-pending:" + scene.saveId, r.turnId);
            await poll(scene.saveId, r.turnId, generation.current);
        } catch (e) {
            report(e);
            setBusy(false);
        }
    }

    async function changeMemory(m: Memory, correction: string | null) {
        try {
            setScene(
                await api<Scene>(base + "/memories/" + m.id, "PATCH", {
                    expectedRevision: scene!.revision,
                    text: correction,
                }),
            );
            setEditing(null);
        } catch (e) {
            report(e);
        }
    }

    async function exportSave() {
        try {
            const data = await api<unknown>(base + "/export");
            const url = URL.createObjectURL(
                new Blob([JSON.stringify(data, null, 2)], {type: "application/json"}),
            );
            const a = document.createElement("a");
            a.href = url;
            a.download = "spring-festival-save.json";
            a.click();
            URL.revokeObjectURL(url);
        } catch (e) {
            report(e);
        }
    }

    return (
        <div className="app">
            <aside className="sidebar">
                <a className="brand" href="#" aria-label="春祭之約首頁">
                    <span className="brand-mark">✳</span>
                    <strong>
                        春祭之約<small>A PERSISTENT STORY WORLD</small>
                    </strong>
                </a>
                <div className="sidebar-rule"/>
                <span className="eyebrow">你在河畔小城的日子</span>
                <nav>
                    {[
                        ["場景", "◈"],
                        ["地圖", "⌁"],
                        ["人物", "♧"],
                        ["故事日誌", "▤"],
                        ["共同記憶", "✧"],
                        ["設定", "⚙"],
                    ].map(([label, icon]) => (
                        <button
                            key={label}
                            className={tab === label ? "nav active" : "nav"}
                            onClick={() => setTab(label)}
                        >
                            <span>{icon}</span>
                            {label}
                            {tab === label && <i/>}
                        </button>
                    ))}
                </nav>
                <div className="sidebar-bottom">
                    <span className="little-flower">✼</span>
                    <p>
                        故事不會因為你離開而責怪你。
                        <br/>
                        想回來的時候，燈都在。
                    </p>
                    <label className="eyebrow" htmlFor="saves">
                        目前的存檔
                    </label>
                    <select
                        id="saves"
                        value={scene?.saveId || ""}
                        disabled={busy}
                        onChange={(e) => load(e.target.value).catch(report)}
                    >
                        <option value="" disabled>
                            選擇一段故事
                        </option>
                        {saves.map((s) => (
                            <option key={s.id} value={s.id}>
                                {s.name}
                            </option>
                        ))}
                    </select>
                    <div className="mode">
                        <span/>{" "}
                        {settings?.mode === "live" ? "雲端敘事模式" : "離線作者模式"}
                        <small>v0.1</small>
                    </div>
                </div>
            </aside>
            <main>
                <header className="topbar">
          <span>
            河畔小城 <span className="muted">／ {tab}</span>
          </span>
                    <span className="date">
            {scene
                ? `第 ${scene.day} 天　·　${scene.period}　·　${scene.weather}`
                : "春天，故事開始的地方"}{" "}
                        <span className="sun">☀</span>
          </span>
                </header>
                {error && (
                    <div role="alert" className="alert">
                        {error}
                        <button onClick={() => retry().catch(report)}>
                            重新查詢原回合
                        </button>
                        <button aria-label="關閉提示" onClick={() => setError("")}>
                            ×
                        </button>
                    </div>
                )}
                {!scene ? (
                    <section className="welcome">
                        <div className="hero-image">
                            <Landscape/>
                        </div>
                        <span className="eyebrow">
              一座小城 · 兩個朋友 · 一個還沒完成的約定
            </span>
                        <h1>
                            有些故事，
                            <br/>
                            會替你留一盞燈。
                        </h1>
                        <p>
                            暫住河畔的旅館，和 Elia、Miro 一起準備春祭。
                            <br/>
                            你說過的話、一起走過的路，都會留在這個世界。
                        </p>
                        <label>
                            為你的故事取個名字
                            <input
                                value={saveName}
                                maxLength={80}
                                onChange={(e) => setSaveName(e.target.value)}
                            />
                        </label>
                        <div className="actions">
                            <button
                                className="primary"
                                disabled={busy}
                                onClick={() => create(false)}
                            >
                                從第一天開始 ↗
                            </button>
                            <button disabled={busy} onClick={() => create(true)}>
                                五分鐘導覽：春祭前夕
                            </button>
                        </div>
                        <p className="fine">
                            離線模式提供作者事件與固定互動；完整任意文字演出需設定雲端模型。
                        </p>
                    </section>
                ) : (
                    <>
                        {tab === "場景" && (
                            <section className="scene-page">
                                <div className="page-heading">
                                    <div>
                    <span className="eyebrow">
                      第 {Math.min(scene.chapter + 1, 6)} 章　／
                        {chapters[scene.chapter]}
                    </span>
                                        <h1>
                                            {scene.placeName}
                                            <span className="heading-dot">。</span>
                                        </h1>
                                    </div>
                                    <span className="saved">✓ 已保存 · r{scene.revision}</span>
                                </div>
                                <div className="scene-art">
                                    <Landscape place={scene.place}/>
                                    <div className="art-label">
                    <span>
                      ◌ {scene.weather}的{scene.period}
                    </span>
                                        <span>
                      {scene.characters.length
                          ? `${scene.characters.join("、")} 在這裡`
                          : "一段安靜的獨處時光"}
                    </span>
                                    </div>
                                </div>
                                <div className="story-grid">
                                    <article className="story-card">
                                        <div className="story-title">
                                            <span className="stamp">✧</span>
                                            <div>
                                                <span className="eyebrow">此刻的故事</span>
                                                <h2>{scene.eventTitle || "在日常裡多停一會兒"}</h2>
                                            </div>
                                        </div>
                                        <p className="story-text">{scene.description}</p>
                                        {narrative && (
                                            <div className="narrative" aria-live="polite">
                                                <span className="eyebrow">剛剛發生的事</span>
                                                <p>{narrative}</p>
                                            </div>
                                        )}
                                        <span className="eyebrow">你想怎麼做？</span>
                                        <div className="suggestions">
                                            {scene.suggestions.map((s, i) => (
                                                <button
                                                    disabled={busy}
                                                    key={s.id}
                                                    onClick={() => submit(s.id)}
                                                >
                                                    <span className="choice-number">0{i + 1}</span>
                                                    {s.label}
                                                    <span className="arrow">↗</span>
                                                </button>
                                            ))}
                                        </div>
                                        <form
                                            onSubmit={(e) => {
                                                e.preventDefault();
                                                submit();
                                            }}
                                        >
                                            <label className="sr-only" htmlFor="message">
                                                說說你的想法
                                            </label>
                                            <div className="input-wrap">
                                                <input
                                                    id="message"
                                                    value={text}
                                                    maxLength={2000}
                                                    disabled={busy}
                                                    onChange={(e) => setText(e.target.value)}
                                                    placeholder={
                                                        scene.mode === "offline"
                                                            ? "試著問：「還記得北方的約定嗎？」"
                                                            : "說說你的想法，或提出一個行動……"
                                                    }
                                                />
                                                <button
                                                    disabled={busy || !text.trim()}
                                                    type="submit"
                                                    aria-label="送出"
                                                >
                                                    ↑
                                                </button>
                                            </div>
                                        </form>
                                        <div className="story-foot">
                      <span>
                        {busy
                            ? "✧ 正在整理這一段故事……"
                            : "一般聊天不推進時間，生活行動才會。"}
                      </span>
                                            <button disabled={busy} onClick={() => submit("topic")}>
                                                換個話題
                                            </button>
                                            <button disabled={busy} onClick={() => submit("rest")}>
                                                休息一下
                                            </button>
                                        </div>
                                    </article>
                                    <aside className="notes-card">
                                        <span className="eyebrow">夾在書頁裡</span>
                                        <h3>我們記得的事</h3>
                                        {scene.facts.length ? (
                                            scene.facts.map((f) => (
                                                <p className="fact" key={f}>
                                                    <span>✦</span>
                                                    {f}
                                                </p>
                                            ))
                                        ) : (
                                            <p className="muted">第一段共同經歷，正等著開始。</p>
                                        )}
                                        <button
                                            className="text-link"
                                            onClick={() => setTab("共同記憶")}
                                        >
                                            翻開共同記憶 ↗
                                        </button>
                                        <div className="note-divider"/>
                                        <span className="eyebrow">接下來，可以去……</span>
                                        <p>
                                            {
                                                [
                                                    "先在旅館認識 Elia",
                                                    "到舊橋，聊聊北方",
                                                    "到市集，找 Miro 看星燈",
                                                    "到舊橋，看看路況",
                                                    "茶書屋找舊圖，或市集找朋友協作",
                                                    "到渡口，商量旅行",
                                                    "六個地點都有值得停留的日常",
                                                ][scene.chapter]
                                            }
                                        </p>
                                        <button
                                            className="text-link"
                                            onClick={() => setTab("地圖")}
                                        >
                                            看看小城地圖 ↗
                                        </button>
                                        <span className="notes-flower">✼</span>
                                    </aside>
                                </div>
                            </section>
                        )}
                        {tab === "地圖" && (
                            <section className="content-page">
                                <span className="eyebrow">沿著河，慢慢走</span>
                                <h1>小城地圖</h1>
                                <Landscape place={scene.place}/>
                                <div className="place-grid">
                                    {Object.entries(places).map(([id, label], i) => (
                                        <button
                                            className={
                                                scene.place === id ? "place selected" : "place"
                                            }
                                            disabled={
                                                busy ||
                                                scene.place === id ||
                                                (id === "LIGHTHOUSE" && scene.ending !== "departed")
                                            }
                                            key={id}
                                            onClick={() => {
                                                submit("move:" + id);
                                                setTab("場景");
                                            }}
                                        >
                                            <span className="eyebrow">0{i + 1}</span>
                                            <h3>{label}</h3>
                                            <small>
                                                {id === scene.place
                                                    ? "你在這裡"
                                                    : id === "LIGHTHOUSE" && scene.ending !== "departed"
                                                        ? "搭船抵達後開放"
                                                        : "沿河步行 · 一個時段"}{" "}
                                                →
                                            </small>
                                        </button>
                                    ))}
                                </div>
                            </section>
                        )}
                        {tab === "人物" && (
                            <section className="content-page">
                                <span className="eyebrow">他們有自己的盼望，也記得你</span>
                                <h1>小城裡的人</h1>
                                <div className="people">
                                    {[
                                        [
                                            "Elia",
                                            "把期待藏在玩笑裡。想完成春祭的責任，也想看看小城之外。",
                                        ],
                                        [
                                            "Miro",
                                            "熱心的地方故事收藏家。正在為每一盞星燈能準時亮起忙碌。",
                                        ],
                                    ].map(([name, desc]) => (
                                        <article className="person" key={name}>
                                            <div className={"portrait " + name}>
                                                <Portrait name={name}/>
                                            </div>
                                            <h2>{name}</h2>
                                            <p>{desc}</p>
                                            <span className="eyebrow">你們的關係</span>
                                            <p>{scene.relationships[name]}</p>
                                        </article>
                                    ))}
                                </div>
                                <h2>在生活中慢慢學會</h2>
                                <div className="skill-row">
                                    {Object.entries(scene.skills).map(([id, level]) => (
                                        <article key={id}>
                      <span>
                        {
                            (
                                {
                                    cooking: "料理",
                                    observation: "觀察",
                                    folkMagic: "民俗魔法",
                                } as Record<string, string>
                            )[id]
                        }
                      </span>
                                            <strong>{level}</strong>
                                            <small>不同經歷留下不同證據</small>
                                        </article>
                                    ))}
                                </div>
                            </section>
                        )}
                        {tab === "故事日誌" && (
                            <section className="content-page">
                                <span className="eyebrow">發生過的事，都有來處</span>
                                <h1>故事日誌</h1>
                                {journal.length === 0 ? (
                                    <p>你的第一頁，還等著落筆。</p>
                                ) : (
                                    journal.map((j, i) => (
                                        <article className="journal-entry" key={i}>
                      <span className="eyebrow">
                        世界紀錄 {j.revision} · {j.eventId}
                      </span>
                                            <h2>{j.title}</h2>
                                            <p>{j.text}</p>
                                        </article>
                                    ))
                                )}
                                <button
                                    onClick={async () => {
                                        try {
                                            const page = await api<{ items: Journal[] }>(
                                                base + "/journal?cursor=" + journal.length,
                                            );
                                            setJournal([...journal, ...page.items]);
                                        } catch (e) {
                                            report(e);
                                        }
                                    }}
                                >
                                    讀下一頁
                                </button>
                            </section>
                        )}
                        {tab === "共同記憶" && (
                            <section className="content-page">
                                <span className="eyebrow">一起走過的路，不只是一段對話</span>
                                <h1>共同記憶</h1>
                                <p className="muted">
                                    你可以更正或刪除記憶；這不會逆轉已發生的世界事件。
                                </p>
                                <div className="memory-grid">
                                    {memories.length === 0 ? (
                                        <p>這裡將保存你們的共同經歷。</p>
                                    ) : (
                                        memories.map((m) => (
                                            <article className="memory" key={m.id}>
                        <span className="eyebrow">
                          {m.pinned
                              ? "✦ 重要約定"
                              : (
                                  {
                                      WORKING: "最近對話",
                                      EPISODIC: "共同經歷",
                                      SEMANTIC: "偏好與事實",
                                      RELATIONSHIP: "關係",
                                      STORY: "故事線索",
                                  } as Record<string, string>
                              )[m.layer]}
                        </span>
                                                {editing === m.id ? (
                                                    <>
                            <textarea
                                aria-label="記憶更正內容"
                                value={editText}
                                maxLength={2000}
                                onChange={(e) => setEditText(e.target.value)}
                            />
                                                        <button
                                                            onClick={() => changeMemory(m, editText)}
                                                            disabled={!editText.trim() || busy}
                                                        >
                                                            保存更正
                                                        </button>
                                                        <button onClick={() => setEditing(null)}>
                                                            取消
                                                        </button>
                                                    </>
                                                ) : (
                                                    <>
                                                        <p>{m.correction || m.text}</p>
                                                        <small>
                                                            來源：{m.sourceId}
                                                            {m.correction ? " · 已更正" : ""}
                                                        </small>
                                                        <div className="memory-actions">
                                                            <button
                                                                disabled={busy}
                                                                onClick={() => {
                                                                    setEditing(m.id);
                                                                    setEditText(m.correction || m.text);
                                                                }}
                                                            >
                                                                更正
                                                            </button>
                                                            <button
                                                                disabled={busy}
                                                                onClick={() => {
                                                                    if (
                                                                        window.confirm(
                                                                            "刪除這筆記憶？已發生的世界事件仍會保留。",
                                                                        )
                                                                    )
                                                                        changeMemory(m, null);
                                                                }}
                                                            >
                                                                刪除
                                                            </button>
                                                        </div>
                                                    </>
                                                )}
                                            </article>
                                        ))
                                    )}
                                </div>
                            </section>
                        )}
                        {tab === "設定" && (
                            <section className="content-page settings">
                                <span className="eyebrow">讓故事安心保存</span>
                                <h1>設定與存檔</h1>
                                <article>
                                    <h2>資料與模型</h2>
                                    <p>{settings?.privacy}</p>
                                    <p>
                                        目前模式：
                                        <strong>
                                            {scene.mode === "offline"
                                                ? "離線作者模式"
                                                : "雲端敘事模式"}
                                        </strong>
                                        。離線可完成作者分支；任意自由輸入的能力有限。
                                    </p>
                                </article>
                                <article>
                                    <h2>另一段故事</h2>
                                    <label>
                                        切換存檔
                                        <select
                                            value={scene.saveId}
                                            disabled={busy}
                                            onChange={(e) => load(e.target.value).catch(report)}
                                        >
                                            {saves.map((save) => (
                                                <option key={save.id} value={save.id}>
                                                    {save.name}
                                                </option>
                                            ))}
                                        </select>
                                    </label>
                                    <label>
                                        新存檔名稱
                                        <input
                                            value={saveName}
                                            onChange={(e) => setSaveName(e.target.value)}
                                            maxLength={80}
                                        />
                                    </label>
                                    <div className="actions">
                                        <button disabled={busy} onClick={() => create(false)}>
                                            建立獨立存檔
                                        </button>
                                        <button disabled={busy} onClick={() => create(true)}>
                                            建立春祭前夕範例
                                        </button>
                                    </div>
                                </article>
                                <SaveManager saves={saves} currentId={scene.saveId} busy={busy} onBusy={setBusy}
                                             onRefresh={refreshSaves} onDeleted={afterDelete} onError={report}/>
                                <article>
                                    <h2>帶著故事走</h2>
                                    <div className="actions">
                                        <button onClick={exportSave}>匯出目前存檔</button>
                                        <button
                                            disabled={busy}
                                            onClick={() => fileInput.current?.click()}
                                        >
                                            匯入為獨立存檔
                                        </button>
                                        <input
                                            type="file"
                                            hidden
                                            ref={fileInput}
                                            accept="application/json,.json"
                                            onChange={async (e) => {
                                                const file = e.target.files?.[0];
                                                if (!file) return;
                                                try {
                                                    if (file.size > 2_000_000)
                                                        throw new Error("檔案不得超過 2 MB。");
                                                    const s = await api<Scene>(
                                                        "/saves/import",
                                                        "POST",
                                                        JSON.parse(await file.text()),
                                                    );
                                                    await refreshSaves();
                                                    await load(s.saveId);
                                                } catch (err) {
                                                    report(err);
                                                }
                                                e.target.value = "";
                                            }}
                                        />
                                    </div>
                                </article>
                                <article>
                                    <h2>透過 MCP 延續同一段故事</h2>
                                    <p>
                                        連線只授權目前存檔。權杖只顯示一次，請放在客戶端的
                                        Authorization Bearer header。
                                    </p>
                                    <button
                                        disabled={!settings?.mcpEnabled}
                                        onClick={async () => {
                                            try {
                                                const r = await api<{ token: string }>(
                                                    base + "/connections",
                                                    "POST",
                                                    {},
                                                );
                                                setToken(r.token);
                                                setConnections(await api(base + "/connections"));
                                            } catch (e) {
                                                report(e);
                                            }
                                        }}
                                    >
                                        建立連線權杖
                                    </button>
                                    {!settings?.mcpEnabled && (
                                        <p className="fine">
                                            啟用方式：設定 WORLD_MCP_ENABLED=true 後重新啟動後端。
                                        </p>
                                    )}
                                    {token && (
                                        <div className="token">
                                            <label>
                                                連線權杖
                                                <input readOnly value={token}/>
                                            </label>
                                            <button
                                                onClick={() =>
                                                    navigator.clipboard.writeText(token).catch(report)
                                                }
                                            >
                                                複製
                                            </button>
                                        </div>
                                    )}
                                    {connections.map((c) => (
                                        <p key={c.id} className="connection">
                                            <code>{c.id.slice(0, 8)}…</code>
                                            <span>{c.revoked ? "已撤銷" : "有效"}</span>
                                            <button
                                                disabled={c.revoked}
                                                onClick={async () => {
                                                    try {
                                                        await api(base + "/connections/" + c.id, "DELETE");
                                                        setConnections(await api(base + "/connections"));
                                                        setToken("");
                                                    } catch (e) {
                                                        report(e);
                                                    }
                                                }}
                                            >
                                                撤銷
                                            </button>
                                        </p>
                                    ))}
                                </article>
                                {settings?.debugEnabled && (
                                    <article>
                                        <h2>開發觀察工具</h2>
                                        <p>
                                            顯示 graph 節點、planner actions
                                            與記憶來源，不顯示模型內部思考。
                                        </p>
                                        <button
                                            disabled={!lastTurn}
                                            onClick={async () => {
                                                try {
                                                    const t = await api<Turn>(
                                                        base + "/debug/" + lastTurn,
                                                    );
                                                    setTrace(t.result?.trace);
                                                } catch (e) {
                                                    report(e);
                                                }
                                            }}
                                        >
                                            檢視上一回合
                                        </button>
                                        {!!trace && <pre>{JSON.stringify(trace, null, 2)}</pre>}
                                    </article>
                                )}
                            </section>
                        )}
                    </>
                )}
                <footer>
                    春祭之約 <span>✦</span> 每一次回來，都是故事的下一頁。
                    <small>JCConfTW26 · AGENT STACK</small>
                </footer>
            </main>
        </div>
    );
}
