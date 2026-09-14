const places: Record<string, string> = {
    INN: "旅館",
    MARKET: "市集",
    TEAHOUSE: "茶書屋",
    BRIDGE: "舊橋",
    DOCK: "渡口",
    LIGHTHOUSE: "北方星燈台",
};

export function Landscape({place = "INN"}: { place?: string }) {
    return (
        <svg
            viewBox="0 0 1000 420"
            className="landscape"
            role="img"
            aria-label={`${places[place]}，春祭前的河畔插畫`}
        >
            <defs>
                <linearGradient id="sky" x2="0" y2="1">
                    <stop stopColor="#c9d9ce"/>
                    <stop offset="1" stopColor="#f3e7cb"/>
                </linearGradient>
                <linearGradient id="river" x2="0" y2="1">
                    <stop stopColor="#9dbfba"/>
                    <stop offset="1" stopColor="#67928a"/>
                </linearGradient>
                <pattern id="grain" width="7" height="7" patternUnits="userSpaceOnUse">
                    <circle cx="1" cy="3" r=".65" fill="#34554b" opacity=".09"/>
                </pattern>
            </defs>
            <path fill="url(#sky)" d="M0 0h1000v420H0z"/>
            <circle cx="769" cy="86" r="42" fill="#fff1c9"/>
            <path
                d="M0 173Q105 32 259 164Q414 53 581 169Q735 63 1000 182V420H0"
                fill="#9fb5a1"
            />
            <path
                d="M0 214Q159 110 297 212Q545 123 681 215Q827 125 1000 211V420H0"
                fill="#809b85"
            />
            <path
                d="M357 196Q645 174 670 244T1000 284V420H170Q291 353 338 308T357 196"
                fill="url(#river)"
            />
            <g fill="none" stroke="#d4e0ca" opacity=".65" strokeWidth="2">
                <path d="M477 266h102m-195 48h67m310 5h82M620 354h180m-421 33h153m370-35h65"/>
            </g>
            <path d="M0 274Q139 228 369 278L277 420H0" fill="#b2b194"/>
            <path d="M0 373Q175 294 326 320l-20 18Q159 332 0 409" fill="#ded3b5"/>
            <g stroke="#586c57" strokeWidth="3">
                <path d="M536 237Q701 128 890 239" fill="none" strokeWidth="21"/>
                <path
                    d="M537 220Q706 111 889 223"
                    fill="none"
                    stroke="#d9cfb0"
                    strokeWidth="7"
                />
                <path d="M550 226v49m30-62v35m31-51v29m32-41v27m33-34v27m34-29v25m35-22v26m34-16v27m32-13v30m33-11v32"/>
            </g>
            {["INN", "TEAHOUSE"].includes(place) && (
                <g transform="translate(70 106)">
                    <path d="M12 124h207v150H12z" fill="#e6d9b8"/>
                    <path d="M-9 132L103 39l136 93z" fill="#725c50"/>
                    <path
                        d="M7 120L103 54l116 71"
                        fill="none"
                        stroke="#b29478"
                        strokeWidth="5"
                    />
                    <path
                        d="M36 147h43v52H36zm110 0h43v52h-43z"
                        fill="#efd389"
                        stroke="#876f53"
                        strokeWidth="5"
                    />
                    <path
                        d="M58 147v52m110-52v52m-69 75V162h30v112"
                        stroke="#947654"
                        strokeWidth="5"
                        fill="#927254"
                    />
                    <path d="M0 220h231v15H0" fill="#687567"/>
                    <path d="M165 71V29h22v54" fill="#a28a70"/>
                    <rect x="89" y="110" width="36" height="29" rx="3" fill="#f3e8c6"/>
                    <text
                        x="107"
                        y="130"
                        fontSize="14"
                        textAnchor="middle"
                        fill="#667360"
                    >
                        {place === "TEAHOUSE" ? "茶" : "宿"}
                    </text>
                </g>
            )}
            {place === "MARKET" && (
                <g transform="translate(25 205)">
                    {[0, 110, 220].map((x, i) => (
                        <g key={x} transform={`translate(${x} ${(i % 2) * 20})`}>
                            <path d="M5 20h90v95H5z" fill="#d9c8a4"/>
                            <path
                                d="M-5 25L15-25h65l25 50z"
                                fill={["#9c7660", "#788e6c", "#b3a06d"][i]}
                            />
                            <path d="M12 35h75v30H12" fill="#586e58"/>
                            <path d="M5 85h90" stroke="#9e8260" strokeWidth="8"/>
                            <circle cx="28" cy="77" r="9" fill="#cba366"/>
                            <circle cx="54" cy="77" r="9" fill="#aab06e"/>
                        </g>
                    ))}
                </g>
            )}
            {place === "DOCK" && (
                <g>
                    <path d="M20 290l290 25-10 34L15 325z" fill="#9a8665"/>
                    <path
                        d="M42 303v72m94-62v58m99-48v52"
                        stroke="#76634e"
                        strokeWidth="10"
                    />
                    <path d="M250 328q125 65 250-5l-28 51H280z" fill="#775f4c"/>
                    <path
                        d="M375 330V195l-82 112h78"
                        fill="#eadfc3"
                        stroke="#8c785e"
                        strokeWidth="3"
                    />
                </g>
            )}
            {place === "BRIDGE" && (
                <g>
                    <path
                        d="M0 303Q168 225 329 308"
                        fill="none"
                        stroke="#b4b097"
                        strokeWidth="32"
                    />
                    <path
                        d="M0 281Q163 204 329 287"
                        fill="none"
                        stroke="#d8ccb0"
                        strokeWidth="9"
                    />
                    <path
                        d="M25 282v63m60-79v62m65-73v54m65-47v56m64-39v51"
                        stroke="#bdb79c"
                        strokeWidth="12"
                    />
                    <path
                        d="M30 254Q160 278 280 256"
                        fill="none"
                        stroke="#a9695d"
                        strokeWidth="4"
                    />
                </g>
            )}
            <g fill="#586c52">
                <path d="M14 174Q-55 36 24 21Q87 67 38 164z"/>
                <path d="M964 246Q891 93 968 62Q1031 80 1000 246z"/>
                <path d="M0 398Q33 329 59 401Q111 346 143 420H0z"/>
            </g>
            <g stroke="#f5dfaa" strokeWidth="2">
                <path d="M235 158Q354 220 486 170" fill="none" stroke="#667358"/>
                {[263, 308, 353, 398, 443].map((x, i) => (
                    <g
                        key={x}
                        transform={`translate(${x} ${173 + Math.sin((i / 4) * Math.PI) * 17})`}
                    >
                        <path d="M0 0v14" stroke="#697356"/>
                        <path d="M-8 14h16l-2 20H-6z" fill="#e9bd70"/>
                    </g>
                ))}
            </g>
            <g transform="translate(343 276)">
                <circle cx="0" cy="0" r="9" fill="#e0c19c"/>
                <path d="M-12 34l5-27h15l10 28z" fill="#927065"/>
                <path d="M-5 35l-1 18m14-18l3 18" stroke="#5c5b4e" strokeWidth="5"/>
            </g>
            {place === "LIGHTHOUSE" && (
                <g transform="translate(762 130)">
                    <path d="M0 138L13 0h30l16 138z" fill="#e7dbb8"/>
                    <path d="M4 0l23-20L51 0" fill="#705e50"/>
                    <path d="M17 4h22v20H17" fill="#efd88e"/>
                </g>
            )}
            <path fill="url(#grain)" d="M0 0h1000v420H0z"/>
        </svg>
    );
}

export function Portrait({name}: { name: string }) {
    const elia = name === "Elia";
    return (
        <svg viewBox="0 0 160 180" role="img" aria-label={name + " 的人物插畫"}>
            <rect
                width="160"
                height="180"
                rx="75"
                fill={elia ? "#ded3be" : "#d4dcc5"}
            />
            <path
                d="M22 180q0-70 58-74 58 4 58 74"
                fill={elia ? "#8c7166" : "#758975"}
            />
            <path d="M58 108l22 28 23-28v-13H58" fill="#ddba93"/>
            <ellipse cx="80" cy="68" rx="37" ry="47" fill="#e6c49f"/>
            <path
                d={
                    elia
                        ? "M40 115V60q-2-55 39-48 51-2 44 60l-7 51-13-37 2-45q-16 15-52 14l-2 58z"
                        : "M40 64q-11-28 12-39 2-20 31-14 42-4 40 55l-18-23-19 6-15-12-19 15z"
                }
                fill={elia ? "#705b4c" : "#6b6651"}
            />
            <path
                d="M62 72h7m24 0h7"
                stroke="#635647"
                strokeWidth="3"
                strokeLinecap="round"
            />
            <path d="M73 89q8 7 16-1" stroke="#a5785c" fill="none" strokeWidth="2"/>
            <path
                d={
                    elia
                        ? "M46 120l33 25 34-25-7 46H54z"
                        : "M49 117l35 15 25-15 9 20-37 10-35-15z"
                }
                fill={elia ? "#e6d9ba" : "#c5a66e"}
            />
            <path d="M80 146v34" stroke="#b29a79" strokeWidth="2"/>
            <circle cx="85" cy="155" r="2" fill="#e2cb9e"/>
            {elia && <path d="M113 49l12-8 1 17-13-4-7 7-3-15z" fill="#a3ac83"/>}
        </svg>
    );
}
