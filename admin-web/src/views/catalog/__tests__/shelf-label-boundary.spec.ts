import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const component = readFileSync(new URL('../components/ShelfLabelPanel.vue', import.meta.url), 'utf8');
const api = readFileSync(new URL('../../../api/catalog/index.ts', import.meta.url), 'utf8');

describe('T2-LBL-001 shelf-label Web boundary', () => {
  it('renders the server preview as text and never executes HTML', () => {
    expect(component).toContain('<pre class="label-preview');
    expect(component).not.toContain('v-html');
    expect(component).toContain('纯文本');
  });

  it('labels replacement confirmation separately from printer success', () => {
    expect(component).toContain('不代表打印成功');
    expect(component).toContain('DISPATCH_BLOCKED');
    expect(component).toContain('BLOCKED_EXTERNAL');
  });

  it('uses formal APIs and never accepts client tenant claims', () => {
    expect(api).toContain('trustedCatalogPayload');
    expect(api).toContain('/dispatch');
    expect(component).not.toMatch(/tenantId\s*:/);
    expect(component).not.toMatch(/USB|Bluetooth|SerialPort|MethodChannel/);
  });
});
