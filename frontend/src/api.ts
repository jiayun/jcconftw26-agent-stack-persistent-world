export type Scene = {
    saveId: string;
    name: string;
    revision: number;
    day: number;
    period: string;
    weather: string;
    place: string;
    placeName: string;
    characters: string[];
    chapter: number;
    eventTitle: string | null;
    description: string;
    suggestions: { id: string; label: string }[];
    facts: string[];
    ending: string | null;
    relationships: Record<string, string>;
    skills: Record<string, string>;
    mode: string;
};
export type Save = {
    id: string;
    name: string;
    revision: number;
    day: number;
    chapter: number;
    placeName: string;
    ending: string | null;
    busy: boolean
};
export type Memory = {
    id: string;
    layer: string;
    text: string;
    correction: string | null;
    sourceId: string;
    pinned: boolean;
};
export type Journal = {
    eventId: string;
    title: string;
    text: string;
    revision: number;
};
export type Turn = {
    id: string;
    status: string;
    error?: string;
    result?: {
        narrative: string;
        revision: number;
        scene: Scene;
        trace?: unknown;
    };
};
export const places: Record<string, string> = {
    INN: "旅館",
    MARKET: "市集",
    TEAHOUSE: "茶書屋",
    BRIDGE: "舊橋",
    DOCK: "渡口",
    LIGHTHOUSE: "北方星燈台",
};
export const chapters = [
    "落腳與相識",
    "北方的約定",
    "星燈異常",
    "道路受阻",
    "完成春祭",
    "兌現與回望",
    "故事仍在繼續",
];

export async function api<T>(
    path: string,
    method = "GET",
    body?: unknown,
): Promise<T> {
    const res = await fetch("/api" + path, {
        method,
        signal: AbortSignal.timeout(15000),
        headers: body === undefined ? {} : {"Content-Type": "application/json"},
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) {
        const text = await res.text();
        let message = `請求未完成（${res.status}）`;
        try {
            message = JSON.parse(text).error || message;
        } catch {
        }
        throw new Error(message);
    }
    return res.json();
}
