// Run with Playwright available in NODE_PATH against an empty, isolated offline server.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const base = process.env.SAVE_TEST_URL || 'http://127.0.0.1:8081';
const output = process.env.SAVE_TEST_OUTPUT || '/tmp/persistent-world-sources';
(async () => {
    await fs.mkdir(output, {recursive: true});
    const browser = await chromium.launch({
        headless: true,
        executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
    });
    try {
        const context = await browser.newContext({viewport: {width: 1280, height: 900}, acceptDownloads: true});
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        const api = async (path, data) => {
            const r = await context.request.fetch(base + '/api' + path, {method: data ? 'POST' : 'GET', ...(data ? {data} : {})});
            assert(r.ok(), await r.text());
            return r.json();
        };
        assert.equal((await api('/settings')).mode, 'offline');
        assert.equal((await api('/saves')).length, 0, 'Use an empty isolated database; never run against existing saves.');
        const a = await api('/saves', {name: '介面驗收甲', demo: false});
        const b = await api('/saves', {name: '介面驗收乙', demo: true});
        const c = await api('/saves', {name: '介面驗收保留', demo: false});
        await page.goto(base);
        await page.getByLabel('目前的存檔', {exact: true}).selectOption(a.saveId);
        await page.getByRole('button', {name: '⚙ 設定', exact: true}).click();
        const manager = page.locator('.save-manager');
        await manager.waitFor();
        await page.getByLabel('搜尋存檔名稱').fill('驗收乙');
        await page.getByRole('button', {name: '勾選顯示項目', exact: true}).click();
        await page.getByRole('button', {name: '刪除已選的 1 個存檔', exact: true}).click();
        await page.getByRole('dialog').waitFor();
        assert((await page.getByRole('dialog').innerText()).includes(b.name));
        await page.getByRole('button', {name: '取消', exact: true}).click();
        assert.equal((await api('/saves')).length, 3);
        await page.getByLabel('搜尋存檔名稱').fill('');
        await manager.getByRole('checkbox', {name: /介面驗收甲/}).check();
        const downloadPromise = page.waitForEvent('download');
        await manager.getByRole('button', {name: /^匯出 介面驗收甲/}).click();
        const download = await downloadPromise;
        const backup = JSON.parse(await fs.readFile(await download.path(), 'utf8'));
        assert.equal(backup.id, a.saveId);
        await manager.screenshot({path: output + '/save-management-desktop.png'});
        await page.getByRole('button', {name: '刪除已選的 2 個存檔', exact: true}).click();
        await page.getByRole('dialog').screenshot({path: output + '/save-management-confirm.png'});
        await page.getByRole('button', {name: '確認永久刪除', exact: true}).click();
        await page.waitForFunction(id => localStorage.getItem('spring-save') === id, c.saveId);
        assert.deepEqual((await api('/saves')).map(s => s.id), [c.saveId]);
        await manager.getByRole('checkbox').check();
        await page.getByRole('button', {name: '刪除已選的 1 個存檔', exact: true}).click();
        const turn = await api(`/saves/${c.saveId}/turns`, {
            requestId: 'stale-delete',
            expectedRevision: 0,
            suggestionId: 'rest'
        });
        for (let i = 0; i < 100; i++) {
            const result = await api(`/saves/${c.saveId}/turns/${turn.turnId}`);
            if (result.status === 'COMPLETE') break;
            if (i === 99) throw Error('Turn timed out');
            await new Promise(r => setTimeout(r, 100));
        }
        await page.getByRole('button', {name: '確認永久刪除', exact: true}).click();
        await page.getByText(/存檔「介面驗收保留」已更新/).waitFor();
        assert.equal((await api('/saves')).length, 1);
        await page.setViewportSize({width: 390, height: 844});
        await manager.screenshot({path: output + '/save-management-mobile.png'});
        assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
        await page.getByRole('button', {name: '刪除已選的 1 個存檔', exact: true}).click();
        await page.getByRole('dialog').screenshot({path: output + '/save-management-mobile-confirm.png'});
        await page.getByRole('button', {name: '確認永久刪除', exact: true}).click();
        await page.getByRole('button', {name: '從第一天開始 ↗', exact: true}).waitFor();
        assert.equal((await api('/saves')).length, 0);
        assert.equal(await page.evaluate(() => localStorage.getItem('spring-save')), null);
        await page.reload();
        await page.getByRole('button', {name: '從第一天開始 ↗', exact: true}).waitFor();
        assert.equal(await page.getByText('找不到這個存檔。', {exact: true}).count(), 0);
        assert.deepEqual(errors, []);
        const report = {passed: ['搜尋與勾選', '取消不刪除', '匯出正確存檔', '批次刪除', '目前存檔自動切換', '過期確認拒絕且保留資料', '手機版無水平溢出', '最後一個刪除後回首頁', '重新載入無過期存檔錯誤', '無瀏覽器程式錯誤']};
        await fs.writeFile(output + '/save-management-ui.json', JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify(report));
    } finally {
        await browser.close();
    }
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
