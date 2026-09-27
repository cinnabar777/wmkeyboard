/**
 * The wmkeyboard:// link builder on /reference/link-builder/.
 *
 * A Preact island (the addon store already pays for the framework) rather
 * than a vanilla `<script>`, because it is five forms sharing one output
 * panel, and the output re-renders on every keystroke. Every rule about what
 * a link may hold lives in src/lib/deep-links.ts, a port of the app's own
 * parsers; this file is only the form.
 *
 * Nothing here branches SSR markup on `navigator`: the "Open in app" and
 * "Share" buttons appear from an effect, so the server render and the first
 * client render agree.
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import {
	LICENSE_ASSETS, MODE_IDS, PANEL_NAMES, ROUTES, ROUTE_GROUPS, SCRIPT_NAMES, STORAGE_IDS, TOOL_NAMES, humanize,
	type ArgSpec, type RouteSpec,
} from '../lib/deep-link-routes';
import {
	SETTING_NAME, adbCommand, adbExtrasCommand, addonLink, addonsLink, customLink, explain, fillRoute, intentUrl,
	opaqueForm, repoLink, settingLink, settingsLink, type Explanation,
} from '../lib/deep-links';
import { resolveManifestUrl } from '../store/lib/resolve';
import { LATEST_RELEASE, SINCE_AWARE, sinceOf, sinceParamFor, withSince } from '../lib/settings-since';
import { SITE_URL } from '../site.mjs';
import './link-builder.css';

/* ---------- data that loads on demand ---------- */

interface SettingRow {
	title: string;
	name: string;
	route: string;
	screens: string[];
	screen: boolean;
	/**
	 * The pattern of the screen the row is drawn on, when that screen takes an
	 * argument: `mode_edit/{modeId}` for a keyboard mode's editor. Absent on a
	 * row drawn where `route` opens.
	 */
	pattern?: string;
	description?: string;
	help?: string;
}

/** Whether `row` is drawn on the screen `pattern` names. */
function onScreen(row: SettingRow, pattern: string): boolean {
	return (row.pattern ?? row.route) === pattern;
}

let settingsPromise: Promise<SettingRow[]> | null = null;
function loadSettings(): Promise<SettingRow[]> {
	settingsPromise ??= import('../data/settings-links.json').then((m) => (m.default as SettingRow[]));
	return settingsPromise;
}

interface Language {
	id: string;
	name: string;
	english: string;
}
let languagesPromise: Promise<Language[]> | null = null;
function loadLanguages(): Promise<Language[]> {
	languagesPromise ??= import('../data/languages.json').then((m) => (m.default as Language[]));
	return languagesPromise;
}

interface AddonEntry {
	id: string;
	name?: string;
	type?: string;
}
const SEEDED: { label: string; input: string; slug: 'official' | 'sounds' }[] = [
	{ label: 'Official repository', input: 'https://github.com/wasi-master/wmkeyboard-addon-repository', slug: 'official' },
	{ label: 'Monkeytype sounds', input: 'https://github.com/wasi-master/wmkeyboard-monkeytype-sounds', slug: 'sounds' },
];
const snapshotPromises: Partial<Record<'official' | 'sounds', Promise<AddonEntry[]>>> = {};
function loadSnapshot(slug: 'official' | 'sounds'): Promise<AddonEntry[]> {
	snapshotPromises[slug] ??= (slug === 'official'
		? import('../data/addons/official.json')
		: import('../data/addons/sounds.json')
	).then((m) => ((m.default as { addons?: AddonEntry[] }).addons ?? []));
	return snapshotPromises[slug]!;
}

/* ---------- small pieces ---------- */

function useAndroid(): boolean {
	const [android, setAndroid] = useState(false);
	useEffect(() => {
		const nav = navigator as Navigator & { userAgentData?: { platform?: string } };
		setAndroid(/\bAndroid\b/i.test(nav.userAgent) || nav.userAgentData?.platform === 'Android');
	}, []);
	return android;
}

function useCanShare(): boolean {
	const [can, setCan] = useState(false);
	useEffect(() => setCan(typeof navigator.share === 'function'), []);
	return can;
}

function CopyButton({ text, label = 'Copy', small }: { text: string; label?: string; small?: boolean }) {
	const [state, setState] = useState<'idle' | 'done' | 'failed'>('idle');
	useEffect(() => {
		if (state === 'idle') return;
		const t = setTimeout(() => setState('idle'), 1400);
		return () => clearTimeout(t);
	}, [state]);
	return (
		<button
			type="button"
			class={`lb-btn ${small ? 'lb-btn-sm' : ''} ${state === 'done' ? 'lb-btn-ok' : ''}`}
			disabled={!text}
			onClick={() => {
				navigator.clipboard?.writeText(text).then(() => setState('done'), () => setState('failed'));
			}}
		>
			{state === 'done' ? 'Copied' : state === 'failed' ? 'Copy failed' : label}
		</button>
	);
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ComponentChildren }) {
	return (
		<label class="lb-field">
			<span class="lb-label">{label}</span>
			{children}
			{hint && <span class="lb-hint">{hint}</span>}
		</label>
	);
}

function Qr({ text }: { text: string }) {
	const ref = useRef<HTMLCanvasElement>(null);
	const [err, setErr] = useState<string | null>(null);
	useEffect(() => {
		let cancelled = false;
		import('qrcode')
			.then((QR) => {
				if (cancelled || !ref.current) return;
				return QR.toCanvas(ref.current, text, { margin: 1, width: 200, errorCorrectionLevel: 'M', color: { dark: '#111111', light: '#ffffff' } });
			})
			.catch((e) => setErr(String(e)));
		return () => {
			cancelled = true;
		};
	}, [text]);
	return (
		<div class="lb-qr">
			<canvas ref={ref} width={200} height={200} aria-label={`QR code: ${text}`} />
			{err && <span class="lb-hint">{err}</span>}
		</div>
	);
}

/* ---------- the explanation line ---------- */

function SettingContext({ setting, route }: { setting: string; route?: string }) {
	const [rows, setRows] = useState<SettingRow[] | null>(null);
	useEffect(() => {
		loadSettings().then(setRows);
	}, []);
	const row = useMemo(() => {
		if (!rows) return null;
		return (
			rows.find((r) => !r.screen && r.name === setting && (!route || r.route === route)) ??
			rows.find((r) => !r.screen && r.name === setting) ??
			null
		);
	}, [rows, setting, route]);

	if (!row || (!row.description && !row.help)) return null;

	return (
		<div class="lb-setting-card">
			<div class="lb-setting-card-head">
				<strong class="lb-setting-card-title">{row.title}</strong>
				{row.screens.length > 0 && <span class="lb-hint"> {[...row.screens, row.title].join(' › ')}</span>}
				<code>{row.name}</code>
			</div>
			{row.description && <p class="lb-setting-desc">{row.description}</p>}
			{row.help && (
				<div class="lb-setting-help">
					<span class="lb-help-badge">Help text</span>
					<div class="lb-help-text">{row.help}</div>
				</div>
			)}
		</div>
	);
}

function Explain({ link }: { link: string }) {
	const x = useMemo(() => explain(link), [link]);
	const settingName = x.kind === 'setting' ? x.setting : x.kind === 'settings' ? x.setting : undefined;
	const settingRoute = x.kind === 'settings' ? x.route : undefined;
	return (
		<>
			<ExplainView x={x} />
			<SinceView x={x} />
			{settingName && <SettingContext setting={settingName} route={settingRoute} />}
		</>
	);
}

/**
 * Which release first opens a settings link, and what an older copy of the app
 * does with it: from `SINCE_AWARE` on it reads `since=` and says which version
 * to update to, and before that it opens nothing.
 */
function SinceView({ x }: { x: Explanation }) {
	const since = sinceOf(x);
	if (!since || (x.kind !== 'settings' && x.kind !== 'setting')) return null;
	const param = sinceParamFor(since.version);
	const older = param
		? x.since
			? <> An older copy says it needs an update, if it is {SINCE_AWARE} or newer.</>
			: <> Without <code>since={param}</code> an older copy opens nothing and cannot say why.</>
		: null;
	if (!since.released) {
		return (
			<p class="lb-explain lb-warn">
				Not in a release yet: no version up to {LATEST_RELEASE} has this, and it arrives with {since.version} or later.{older}
			</p>
		);
	}
	return (
		<p class="lb-explain">
			Works in WM Keyboard {since.version} and newer.{older}
		</p>
	);
}

/** "The theme editor…" reads as "the theme editor…" mid-sentence. */
function midSentence(label: string): string {
	return label.replace(/^(The|One|A|That) /, (m) => m.toLowerCase());
}

function ExplainView({ x }: { x: Explanation }) {
	switch (x.kind) {
		case 'invalid':
			return <p class="lb-explain lb-bad">Not a link the app reacts to. {x.reason}</p>;
		case 'nowhere':
			return <p class="lb-explain lb-bad">Opens the app, then nothing. {x.reason}</p>;
		case 'settings':
			return (
				<p class="lb-explain lb-ok">
					Opens <strong>{midSentence(x.label)}</strong> (<code>{x.route}</code>)
					{x.setting && (
						<>
							{' '}and scrolls to the row named <code>{x.setting}</code>, pulsing it once
						</>
					)}
					. {x.note}
				</p>
			);
		case 'setting':
			return (
				<p class="lb-explain lb-ok">
					Looks up the row named <code>{x.setting}</code> in the settings index and opens whichever screen holds it, scrolled to
					that row. A copy of the app that does not have the name opens no screen and says so.
				</p>
			);
		case 'addons':
			return <p class="lb-explain lb-ok">Opens the Add-ons screen.</p>;
		case 'repo':
			return (
				<p class="lb-explain lb-ok">
					Opens Add-ons with the add-repository dialog pre-filled. The app resolves <code>{x.input}</code> to{' '}
					<code>{x.manifest}</code> and fetches nothing until you press Add.
				</p>
			);
		case 'addon':
			return (
				<p class="lb-explain lb-ok">
					Opens the page of the addon <code>{x.id}</code> in the repository at <code>{x.manifest}</code>. Nothing installs until
					you press Install.
				</p>
			);
		case 'oauth':
			return (
				<p class="lb-explain lb-warn">
					The sign-in redirect for Dropbox and OneDrive. It carries only an authorization code and does nothing when opened by
					hand.
				</p>
			);
	}
}

/* ---------- the output panel ---------- */

const FALLBACK_DEFAULT = 'https://wmkeyboard.pages.dev/start/installation/';

function Output({ link, route, setting, incomplete }: { link: string; route?: string; setting?: string; incomplete?: boolean }) {
	const android = useAndroid();
	const canShare = useCanShare();
	const [label, setLabel] = useState('Open in WM Keyboard');
	const [fallback, setFallback] = useState(FALLBACK_DEFAULT);
	const [showQr, setShowQr] = useState(false);
	const [showMore, setShowMore] = useState(false);
	const extras = route !== undefined ? adbExtrasCommand(route, setting ?? '') : null;
	const intent = intentUrl(link, fallback.trim() || undefined);

	return (
		<section class="lb-output" aria-live="polite">
			<div class="lb-output-head">
				<span class="lb-label">Your link</span>
				<div class="lb-actions">
					<CopyButton text={link} />
					{android && (
						<a class="lb-btn lb-btn-primary" href={link}>
							Open in app
						</a>
					)}
					{canShare && (
						<button type="button" class="lb-btn" onClick={() => navigator.share({ text: link }).catch(() => {})}>
							Share
						</button>
					)}
					<button type="button" class="lb-btn" aria-pressed={showQr} onClick={() => setShowQr((v) => !v)}>
						QR
					</button>
				</div>
			</div>
			<pre class="lb-link"><code>{link}</code></pre>
			{!incomplete && <Explain link={link} />}
			{showQr && <Qr text={link} />}

			<button type="button" class="lb-disclosure" aria-expanded={showMore} onClick={() => setShowMore((v) => !v)}>
				{showMore ? 'Fewer spellings' : 'Other spellings: intent://, adb, Markdown, HTML'}
			</button>
			{showMore && (
				<div class="lb-more">
					<Field label="Link text" hint="Used by the Markdown and HTML forms.">
						<input class="lb-input" value={label} onInput={(e) => setLabel((e.target as HTMLInputElement).value)} />
					</Field>
					<Spelling title="Markdown" text={`[${label}](${link})`} />
					<Spelling title="HTML" text={`<a href="${link.replace(/"/g, '&quot;')}">${escapeHtml(label)}</a>`} />
					<Spelling
						title="Web address (https)"
						text={`${SITE_URL}/open/#${link}`}
						note="An https page that offers to open the link, and offers to install the app when it is missing. Use it where a custom scheme is not allowed to be a link at all, such as most chat apps and Markdown renderers."
					/>
					<Spelling
						title="Opaque form"
						text={opaqueForm(link)}
						note="The same address with no slashes after the colon. The app accepts both."
					/>
					<Field
						label="Fallback page for the intent:// form"
						hint="Chrome on Android opens this page instead when WM Keyboard is not installed. Leave empty for no fallback."
					>
						<input class="lb-input" value={fallback} onInput={(e) => setFallback((e.target as HTMLInputElement).value)} />
					</Field>
					<Spelling
						title="intent:// (Chrome on Android)"
						text={intent}
						note="Names the package, so Chrome never asks which app should open it. Other browsers ignore this form; give them the plain link."
					/>
					<Spelling title="adb" text={adbCommand(link)} note="Sends the link from a computer over USB." />
					{extras && (
						<Spelling
							title="adb, as intent extras"
							text={extras}
							note="What another app on the device would put on an explicit intent. Same checks as the link."
						/>
					)}
				</div>
			)}
		</section>
	);
}

function escapeHtml(s: string): string {
	return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function Spelling({ title, text, note }: { title: string; text: string; note?: string }) {
	return (
		<div class="lb-spelling">
			<div class="lb-spelling-head">
				<span class="lb-label">{title}</span>
				<CopyButton text={text} small />
			</div>
			<pre><code>{text}</code></pre>
			{note && <span class="lb-hint">{note}</span>}
		</div>
	);
}

/* ---------- mode: a settings screen ---------- */

function optionsFor(kind: ArgSpec['options'], languages: Language[]): { value: string; label: string }[] | null {
	switch (kind) {
		case 'tools':
			return TOOL_NAMES.map((t) => ({ value: t, label: humanize(t) }));
		case 'panels':
			return PANEL_NAMES.map((p) => ({ value: p, label: humanize(p) }));
		case 'scripts':
			return SCRIPT_NAMES.map((s) => ({ value: s, label: humanize(s) }));
		case 'storage':
			return STORAGE_IDS.map((s) => ({ value: s, label: humanize(s) }));
		case 'licenses':
			return LICENSE_ASSETS.map((s) => ({ value: s, label: s.replace(/\.txt$/, '') }));
		case 'languages':
			return languages.map((l) => ({ value: l.id, label: `${l.english} (${l.id})` }));
		case 'modes':
			return MODE_IDS.map((m) => ({ value: m.id, label: `${m.name} (${m.id})` }));
		default:
			return null;
	}
}

function ArgField({ spec, value, onChange, languages }: { spec: ArgSpec; value: string; onChange: (v: string) => void; languages: Language[] }) {
	const options = optionsFor(spec.options, languages);
	const listId = `lb-arg-${spec.name}-${spec.options ?? 'free'}`;
	// A known set gets a select with a "type your own" escape; user ids are free text.
	if (options && spec.options !== 'languages') {
		const known = options.some((o) => o.value === value);
		return (
			<Field label={spec.name} hint={spec.hint}>
				<div class="lb-row">
					<select class="lb-select" value={known ? value : '__custom'} onChange={(e) => {
						const v = (e.target as HTMLSelectElement).value;
						onChange(v === '__custom' ? '' : v);
					}}>
						{options.map((o) => (
							<option value={o.value}>{o.label}</option>
						))}
						<option value="__custom">Type another…</option>
					</select>
					{!known && <input class="lb-input" value={value} placeholder={spec.hint} onInput={(e) => onChange((e.target as HTMLInputElement).value)} />}
				</div>
			</Field>
		);
	}
	return (
		<Field label={spec.name} hint={spec.hint}>
			<input class="lb-input" list={options ? listId : undefined} value={value} placeholder={spec.hint} onInput={(e) => onChange((e.target as HTMLInputElement).value)} />
			{options && (
				<datalist id={listId}>
					{options.map((o) => (
						<option value={o.value}>{o.label}</option>
					))}
				</datalist>
			)}
		</Field>
	);
}

function SettingPicker({
	rows, route, value, valueRoute, onPick, placeholder,
}: {
	rows: SettingRow[] | null;
	/** Limit to rows on one screen, named by its pattern, or undefined for every screen. */
	route?: string;
	value: string;
	/** The route of the picked row, when the same name is listed on several screens. */
	valueRoute?: string | null;
	onPick: (row: SettingRow | null) => void;
	placeholder: string;
}) {
	const [query, setQuery] = useState('');
	const [open, setOpen] = useState(false);
	const candidates = useMemo(() => {
		if (!rows) return [];
		const pool = route === undefined ? rows.filter((r) => !r.screen) : rowsOn(rows, route);
		const q = query.trim().toLowerCase();
		if (!q) return pool.slice(0, 40);
		const words = q.split(/\s+/);
		return pool
			.map((r) => {
				const hay = `${r.title} ${r.screens.join(' ')} ${r.name} ${r.description ?? ''} ${r.help ?? ''}`.toLowerCase();
				let score = 0;
				for (const w of words) {
					if (!hay.includes(w)) return null;
					if (r.title.toLowerCase().startsWith(w)) score += 5;
					else if (r.title.toLowerCase().includes(w)) score += 3;
					else if (r.description?.toLowerCase().includes(w)) score += 2;
					else if (r.name.includes(w)) score += 1;
					else if (r.help?.toLowerCase().includes(w)) score += 1;
				}
				return { r, score };
			})
			.filter((x): x is { r: SettingRow; score: number } => x !== null)
			.sort((a, b) => b.score - a.score)
			.slice(0, 40)
			.map((x) => x.r);
	}, [rows, route, query]);
	const picked =
		rows?.find(
			(r) => !r.screen && r.name === value && (route === undefined || onScreen(r, route)) && (!valueRoute || r.route === valueRoute),
		) ?? null;

	return (
		<div class="lb-picker">
			<div class="lb-row">
				<input
					class="lb-input"
					type="search"
					placeholder={placeholder}
					value={query}
					onFocus={() => setOpen(true)}
					onInput={(e) => {
						setQuery((e.target as HTMLInputElement).value);
						setOpen(true);
					}}
					aria-label={placeholder}
				/>
				{value && (
					<button type="button" class="lb-btn lb-btn-sm" onClick={() => onPick(null)}>
						Clear
					</button>
				)}
			</div>
			{picked && (
				<div class="lb-picked">
					<div class="lb-picked-header">
						<strong>{picked.title}</strong>
						<span class="lb-hint"> {[...picked.screens, picked.title].join(' › ')}</span>
						<code>{picked.name}</code>
					</div>
					{picked.description && <p class="lb-picked-desc">{picked.description}</p>}
					{picked.help && (
						<div class="lb-picked-help">
							<span class="lb-help-badge">Help text</span>
							<div class="lb-help-text">{picked.help}</div>
						</div>
					)}
				</div>
			)}
			{open && (
				<ul class="lb-results" role="listbox">
					{!rows && <li class="lb-hint">Loading the settings index…</li>}
					{rows && candidates.length === 0 && <li class="lb-hint">No row matches.</li>}
					{candidates.map((r) => (
						<li>
							<button
								type="button"
								role="option"
								aria-selected={r.name === value && (!valueRoute || r.route === valueRoute)}
								onClick={() => {
									onPick(r);
									setOpen(false);
								}}
							>
								<span class="lb-result-title">{r.title}</span>
								{r.description && <span class="lb-result-desc">{r.description}</span>}
								<span class="lb-result-path">{r.screens.join(' › ')}</span>
								<code>{r.name}</code>
							</button>
						</li>
					))}
				</ul>
			)}
		</div>
	);
}

/**
 * The rows drawn on the screen `pattern` names, each once. A shipped keyboard
 * mode's rows are listed once per mode, so a screen with an argument would
 * otherwise offer every row six times over.
 */
function rowsOn(rows: SettingRow[], pattern: string): SettingRow[] {
	const seen = new Set<string>();
	return rows.filter((r) => {
		if (r.screen || !onScreen(r, pattern) || seen.has(r.name)) return false;
		seen.add(r.name);
		return true;
	});
}

function ScreenMode({ rows, languages }: { rows: SettingRow[] | null; languages: Language[] }) {
	const [pattern, setPattern] = useState('themes');
	const [args, setArgs] = useState<Record<string, string>>({});
	const [setting, setSetting] = useState('');
	const spec = ROUTES.find((r) => r.pattern === pattern) as RouteSpec;
	const route = fillRoute(pattern, args);
	const missing = (spec.args ?? []).filter((a) => !(args[a.name] ?? '').trim());
	const raw = settingsLink({ route: pattern === 'home' ? '' : route, setting: setting || undefined });
	// since= only when it says something: see SinceView.
	const link = useMemo(() => withSince(explain(raw)) ?? raw, [raw]);
	const rowsOnScreen = rows ? rowsOn(rows, pattern).length : 0;

	return (
		<>
			<Field label="Screen">
				<select
					class="lb-select"
					value={pattern}
					onChange={(e) => {
						setPattern((e.target as HTMLSelectElement).value);
						setArgs({});
						setSetting('');
					}}
				>
					{ROUTE_GROUPS.map((g) => (
						<optgroup label={g}>
							{ROUTES.filter((r) => r.group === g).map((r) => (
								<option value={r.pattern}>
									{r.label} · {r.pattern}
								</option>
							))}
						</optgroup>
					))}
				</select>
			</Field>
			{spec.args?.map((a) => (
				<ArgField spec={a} value={args[a.name] ?? ''} onChange={(v) => setArgs((s) => ({ ...s, [a.name]: v }))} languages={languages} />
			))}
			{missing.length > 0 && (
				<p class="lb-explain lb-warn">
					Fill in {missing.map((a) => a.name).join(' and ')}. An empty segment makes the whole address invalid.
				</p>
			)}
			{(!spec.args || rowsOnScreen > 0) && (
				<Field
					label="Scroll to one row on this screen (optional)"
					hint={rows ? `${rowsOnScreen} rows on this screen can be named.` : undefined}
				>
					<SettingPicker rows={rows} route={pattern} value={setting} onPick={(r) => setSetting(r?.name ?? '')} placeholder="Search rows on this screen…" />
				</Field>
			)}
			<Output link={link} route={pattern === 'home' ? '' : route} setting={setting} incomplete={missing.length > 0} />
		</>
	);
}

/* ---------- mode: one setting ---------- */

function SettingMode({ rows }: { rows: SettingRow[] | null }) {
	const [name, setName] = useState('typing_autocorrect_title');
	// Which copy was picked, for a name listed on several screens: a keyboard
	// mode's rows appear once per shipped mode.
	const [pickedRoute, setPickedRoute] = useState<string | null>(null);
	const [typed, setTyped] = useState(false);
	const [withScreen, setWithScreen] = useState(false);
	const picked =
		rows?.find((r) => r.name === name && !r.screen && (!pickedRoute || r.route === pickedRoute)) ?? null;
	const valid = SETTING_NAME.test(name);
	const raw = withScreen && picked ? settingsLink({ route: picked.route, setting: name }) : settingLink(name);
	const link = useMemo(() => (valid ? withSince(explain(raw)) ?? raw : raw), [raw, valid]);

	return (
		<>
			<Field label="Find the setting" hint="Search by what the row says, the screen it is on, or its resource name.">
				<SettingPicker
					rows={rows}
					value={name}
					valueRoute={pickedRoute}
					onPick={(r) => {
						setName(r?.name ?? '');
						setPickedRoute(r?.route ?? null);
						setTyped(false);
						// One mode's row reaches that mode only with its screen named:
						// on its own the name opens the list of modes.
						if (r?.pattern && r.route.split('/').length === r.pattern.split('/').length) setWithScreen(true);
					}}
					placeholder="Autocorrect, key popup, haptics…"
				/>
			</Field>
			<Field label="Or type the resource name" hint="Lowercase letters, digits and underscores, starting with a letter. Up to 128 characters.">
				<input
					class="lb-input lb-mono"
					value={name}
					aria-invalid={!valid}
					onInput={(e) => {
						setName((e.target as HTMLInputElement).value.trim());
						setPickedRoute(null);
						setTyped(true);
					}}
				/>
			</Field>
			{typed && name && valid && !picked && rows && (
				<p class="lb-explain lb-warn">
					No row in the settings index is called <code>{name}</code>. The link parses, but the app finds nothing to open.
				</p>
			)}
			<label class="lb-check">
				<input type="checkbox" checked={withScreen} disabled={!picked} onChange={(e) => setWithScreen((e.target as HTMLInputElement).checked)} />
				<span>
					Name the screen too
					{picked && (
						<span class="lb-hint">
							{' '}
							(<code>settings/{picked.route}?setting=…</code>). Pins the row to this screen when the same name is indexed on more than one.
						</span>
					)}
				</span>
			</label>
			<Output link={link} route={withScreen && picked ? picked.route : ''} setting={name} />
		</>
	);
}

/* ---------- mode: addon store ---------- */

function RepoField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
	const manifest = value.trim() ? resolveManifestUrl(value) : null;
	const preset = SEEDED.find((s) => s.input === value.trim());
	return (
		<>
			<Field label="Repository" hint="A GitHub, Codeberg, GitLab, sourcehut or Bitbucket page, a folder URL, or the manifest URL itself. Same rules as the app's Add dialog.">
				<div class="lb-row">
					<select class="lb-select" value={preset?.input ?? '__custom'} onChange={(e) => {
						const v = (e.target as HTMLSelectElement).value;
						onChange(v === '__custom' ? '' : v);
					}}>
						{SEEDED.map((s) => (
							<option value={s.input}>{s.label}</option>
						))}
						<option value="__custom">Another repository…</option>
					</select>
				</div>
				<input class="lb-input lb-mono" value={value} placeholder="https://github.com/user/repo" aria-invalid={value.trim() !== '' && !manifest} onInput={(e) => onChange((e.target as HTMLInputElement).value)} />
			</Field>
			{value.trim() && (
				manifest ? (
					<p class="lb-explain lb-ok">
						Resolves to <code>{manifest}</code>.
					</p>
				) : (
					<p class="lb-explain lb-bad">The app cannot resolve this address. It needs https and a host with a dot.</p>
				)
			)}
		</>
	);
}

function AddonMode() {
	const [kind, setKind] = useState<'addons' | 'repo' | 'addon'>('addon');
	const [repo, setRepo] = useState(SEEDED[0]!.input);
	const [id, setId] = useState('');
	const [entries, setEntries] = useState<AddonEntry[] | null>(null);
	const preset = SEEDED.find((s) => s.input === repo.trim());

	useEffect(() => {
		if (!preset) {
			setEntries(null);
			return;
		}
		let cancelled = false;
		loadSnapshot(preset.slug).then((list) => {
			if (!cancelled) setEntries(list);
		});
		return () => {
			cancelled = true;
		};
	}, [preset?.slug]);

	const link = kind === 'addons' ? addonsLink() : kind === 'repo' ? repoLink(repo) : addonLink(repo, id);

	return (
		<>
			<Field label="Which page">
				<select class="lb-select" value={kind} onChange={(e) => setKind((e.target as HTMLSelectElement).value as typeof kind)}>
					<option value="addons">The Add-ons screen</option>
					<option value="repo">A repository, ready to add</option>
					<option value="addon">One addon's page</option>
				</select>
			</Field>
			{kind !== 'addons' && <RepoField value={repo} onChange={setRepo} />}
			{kind === 'addon' && (
				<Field label="Addon id" hint={entries ? `The ids in this repository, from the site's last snapshot of it.` : "The addon's id in that repository's manifest."}>
					{entries ? (
						<select class="lb-select" value={id} onChange={(e) => setId((e.target as HTMLSelectElement).value)}>
							<option value="">Choose an addon…</option>
							{entries.map((e) => (
								<option value={e.id}>
									{e.name ?? e.id} · {e.id}
								</option>
							))}
						</select>
					) : (
						<input class="lb-input lb-mono" value={id} placeholder="addon-id" onInput={(e) => setId((e.target as HTMLInputElement).value)} />
					)}
				</Field>
			)}
			<Output link={link} />
		</>
	);
}

/* ---------- mode: by hand ---------- */

const HOSTS = ['settings', 'setting', 'addons', 'repo', 'addon'];

function CustomMode() {
	const [host, setHost] = useState('settings');
	const [otherHost, setOtherHost] = useState('');
	const [path, setPath] = useState('typing/corrections');
	const [query, setQuery] = useState<[string, string][]>([['setting', 'typing_autocorrect_title']]);
	const effectiveHost = host === '__other' ? otherHost : host;
	const link = customLink(effectiveHost, path, query);

	return (
		<>
			<Field label="Host" hint="The word after wmkeyboard://. It picks which part of the app answers.">
				<div class="lb-row">
					<select class="lb-select" value={host} onChange={(e) => setHost((e.target as HTMLSelectElement).value)}>
						{HOSTS.map((h) => (
							<option value={h}>{h}</option>
						))}
						<option value="__other">Something else…</option>
					</select>
					{host === '__other' && <input class="lb-input lb-mono" value={otherHost} placeholder="host" onInput={(e) => setOtherHost((e.target as HTMLInputElement).value)} />}
				</div>
			</Field>
			<Field label="Path" hint="Segments separated by slashes. Percent-encode a slash inside a value as %2F.">
				<input class="lb-input lb-mono" value={path} placeholder="typing/corrections" onInput={(e) => setPath((e.target as HTMLInputElement).value)} />
			</Field>
			<div class="lb-field">
				<span class="lb-label">Query parameters</span>
				{query.map(([k, v], i) => (
					<div class="lb-row">
						<input class="lb-input lb-mono" value={k} placeholder="name" aria-label="Parameter name" onInput={(e) => setQuery((q) => q.map((p, j) => (j === i ? [(e.target as HTMLInputElement).value, p[1]] : p)))} />
						<input class="lb-input lb-mono" value={v} placeholder="value" aria-label="Parameter value" onInput={(e) => setQuery((q) => q.map((p, j) => (j === i ? [p[0], (e.target as HTMLInputElement).value] : p)))} />
						<button type="button" class="lb-btn lb-btn-sm" aria-label="Remove parameter" onClick={() => setQuery((q) => q.filter((_, j) => j !== i))}>
							×
						</button>
					</div>
				))}
				<div>
					<button type="button" class="lb-btn lb-btn-sm" onClick={() => setQuery((q) => [...q, ['', '']])}>
						Add a parameter
					</button>
				</div>
				<span class="lb-hint">Values are percent-encoded for you. The app reads only the parameters its host knows: setting, since, url, repo and id.</span>
			</div>
			<Output link={link} />
		</>
	);
}

/* ---------- mode: read a link ---------- */

function DecodeMode({ initial }: { initial: string }) {
	const [text, setText] = useState(initial);
	useEffect(() => setText(initial), [initial]);
	const x = useMemo(() => explain(text), [text]);
	return (
		<>
			<Field label="Paste a link" hint="Any wmkeyboard:// address, in either spelling. Nothing is opened; this only says what would happen.">
				<textarea class="lb-input lb-mono lb-textarea" value={text} onInput={(e) => setText((e.target as HTMLTextAreaElement).value)} spellcheck={false} />
			</Field>
			{text.trim() && <Explain link={text.trim()} />}
			{text.trim() && x.kind !== 'invalid' && (
				<div class="lb-actions">
					<CopyButton text={text.trim()} />
				</div>
			)}
		</>
	);
}

/* ---------- root ---------- */

type Mode = 'screen' | 'setting' | 'addon' | 'custom' | 'decode';

const MODES: { id: Mode; label: string }[] = [
	{ id: 'setting', label: 'One setting' },
	{ id: 'screen', label: 'A settings screen' },
	{ id: 'addon', label: 'The addon store' },
	{ id: 'custom', label: 'By hand' },
	{ id: 'decode', label: 'Read a link' },
];

export default function LinkBuilder() {
	const [mode, setMode] = useState<Mode>('setting');
	const [rows, setRows] = useState<SettingRow[] | null>(null);
	const [languages, setLanguages] = useState<Language[]>([]);
	const [pasted, setPasted] = useState('');

	useEffect(() => {
		loadSettings().then(setRows);
		loadLanguages().then(setLanguages);
		// /reference/link-builder/?link=wmkeyboard://… opens straight on the reader.
		const fromQuery = new URLSearchParams(location.search).get('link');
		const fromHash = location.hash.startsWith('#wmkeyboard:') ? location.hash.slice(1) : null;
		const initial = fromQuery ?? fromHash;
		if (initial) {
			setPasted(initial);
			setMode('decode');
		}
	}, []);

	return (
		<div class="lb not-content">
			<div class="lb-tabs" role="tablist" aria-label="What to link to">
				{MODES.map((m) => (
					<button type="button" role="tab" aria-selected={mode === m.id} class={`lb-tab ${mode === m.id ? 'is-active' : ''}`} onClick={() => setMode(m.id)}>
						{m.label}
					</button>
				))}
			</div>
			<div class="lb-panel" role="tabpanel">
				{mode === 'screen' && <ScreenMode rows={rows} languages={languages} />}
				{mode === 'setting' && <SettingMode rows={rows} />}
				{mode === 'addon' && <AddonMode />}
				{mode === 'custom' && <CustomMode />}
				{mode === 'decode' && <DecodeMode initial={pasted} />}
			</div>
		</div>
	);
}
