<template>
  <div>
    <h2 class="page-title">挂起仲裁台 · 删除义务 × 保留义务冲突</h2>

    <el-card class="section-card">
      <template #header>登记挂起 / 撤销文书（位置粒度建台账，文书内容哈希冻结）</template>
      <el-form :model="form" label-width="110px" style="max-width:1180px">
        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="文书编号" required>
              <el-input v-model="form.docNo" placeholder="如 劳仲保字〔2026〕017号" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="文书类型" required>
              <el-select v-model="form.docType" style="width:100%">
                <el-option v-for="t in docTypes" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="方向" required>
              <el-radio-group v-model="form.direction">
                <el-radio-button value="HOLD">保全/挂起</el-radio-button>
                <el-radio-button value="RELEASE">保全撤销</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="关联工单号">
              <el-input v-model="form.ticketNo" placeholder="可选，留空按位置反查" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="文书标题" required>
              <el-input v-model="form.title" placeholder="如 劳动争议仲裁保全裁定书" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="出具机关">
              <el-input v-model="form.issuingAuthority" placeholder="如 市劳动人事争议仲裁委员会" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="保全范围" required>
          <el-input v-model="form.scopeText" type="textarea" :rows="2"
            placeholder="文书载明的保全范围原文（登记即哈希冻结，延期另出新版本，原件不改）" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="生效时点" required>
              <el-date-picker v-model="form.effectiveAt" type="datetime" style="width:100%"
                value-format="YYYY-MM-DDTHH:mm:ss" placeholder="文书生效时点" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="文书到期日" required>
              <el-date-picker v-model="form.expiresAt" type="datetime" style="width:100%"
                value-format="YYYY-MM-DDTHH:mm:ss" placeholder="文书整体到期日" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">受影响存储位置（逐项写明，备份/法定留存/第三方副本各自到期）</el-divider>
        <div v-for="(loc, idx) in form.locations" :key="idx" class="loc-row">
          <el-input v-model="loc.assetCode" placeholder="资产编码 如 AST_BACKUP" style="width:170px" />
          <el-input v-model="loc.locationRef" placeholder="存储位置 如 backup/2026-11-12/account.dump" style="width:260px" />
          <el-select v-model="loc.locationKind" placeholder="位置类别" style="width:160px">
            <el-option v-for="k in locationKinds" :key="k.value" :label="k.label" :value="k.value" />
          </el-select>
          <el-input v-model="loc.ownerPerson" placeholder="责任人" style="width:120px" />
          <el-date-picker v-model="loc.expiresAt" type="datetime" style="width:200px"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="位置到期日(可选)" />
          <el-input v-model="loc.scopeDetail" placeholder="范围说明" style="width:200px" />
          <el-button type="danger" plain @click="form.locations.splice(idx, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="addLocation" style="margin:8px 0">+ 增加位置</el-button>
        <div>
          <el-button type="primary" @click="register">登记并建台账</el-button>
          <el-text type="info" size="small">同日到达的撤销与新增保全将自动并列进冲突清单，按文书生效时点排序交法务裁定</el-text>
        </div>
      </el-form>
    </el-card>

    <el-card>
      <el-tabs v-model="activeTab" type="border-card">
        <el-tab-pane label="挂起台账（位置粒度）" name="entries">
          <el-radio-group v-model="entryFilter" @change="loadEntries" style="margin-bottom:10px">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="ACTIVE">生效中</el-radio-button>
            <el-radio-button value="ESCALATED">已升级</el-radio-button>
            <el-radio-button value="RELEASED">已恢复/关闭</el-radio-button>
            <el-radio-button value="EXPIRED">已到期</el-radio-button>
          </el-radio-group>
          <el-table :data="entries" border size="small">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column label="主体哈希" width="150">
              <template #default="{ row }"><span class="mono">{{ row.subject_hash.substring(0, 14) }}…</span></template>
            </el-table-column>
            <el-table-column prop="doc_no" label="文书编号" width="180" show-overflow-tooltip />
            <el-table-column prop="doc_version_no" label="版本" width="70" />
            <el-table-column prop="asset_code" label="资产" width="150" />
            <el-table-column prop="location_ref" label="存储位置" width="240" show-overflow-tooltip />
            <el-table-column label="位置类别" width="140">
              <template #default="{ row }">{{ kindLabel(row.location_kind) }}</template>
            </el-table-column>
            <el-table-column prop="owner_person" label="责任人" width="100" />
            <el-table-column prop="hold_effective_at" label="生效时点" width="190" />
            <el-table-column prop="hold_expires_at" label="到期日" width="190" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="entryStatusType(row.status)">{{ entryStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="裁定状态" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.adjudication_state !== 'CLEAR'" type="danger" size="small">
                  {{ adjudicationLabel(row.adjudication_state) }}
                </el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column prop="extension_count" label="延期次数" width="90" />
            <el-table-column prop="release_reason" label="恢复/关闭原因" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="文书版本（冻结）" name="docs">
          <el-input v-model="docNoFilter" placeholder="按文书编号过滤" style="width:280px;margin-bottom:10px"
            @keyup.enter="loadDocuments" />
          <el-button @click="loadDocuments">查询</el-button>
          <el-table :data="documents" border size="small">
            <el-table-column prop="doc_no" label="文书编号" width="200" />
            <el-table-column prop="version_no" label="版本" width="80" />
            <el-table-column label="方向" width="100">
              <template #default="{ row }">
                <el-tag :type="row.direction === 'HOLD' ? 'danger' : 'success'">
                  {{ row.direction === 'HOLD' ? '保全挂起' : '保全撤销' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" width="220" show-overflow-tooltip />
            <el-table-column prop="effective_at" label="生效时点" width="190" />
            <el-table-column prop="expires_at" label="到期日" width="190" />
            <el-table-column label="内容哈希(冻结)" min-width="240">
              <template #default="{ row }"><span class="mono">{{ row.doc_hash }}</span></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="conflicts">
          <template #label>
            同日冲突裁定
            <el-badge v-if="pendingConflictCount" :value="pendingConflictCount" type="danger" style="margin-left:6px" />
          </template>
          <el-table :data="conflicts" border size="small">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="asset_code" label="资产" width="140" />
            <el-table-column prop="location_ref" label="存储位置" width="220" show-overflow-tooltip />
            <el-table-column label="挂起依据" min-width="230">
              <template #default="{ row }">
                <strong>{{ row.hold_doc_no }} v{{ row.hold_version }}</strong><br />
                <el-text size="small" type="danger">生效 {{ row.hold_effective_at }}</el-text><br />
                <el-text size="small">{{ row.hold_title }}</el-text>
              </template>
            </el-table-column>
            <el-table-column label="撤销依据" min-width="230">
              <template #default="{ row }">
                <strong>{{ row.release_doc_no }} v{{ row.release_version }}</strong><br />
                <el-text size="small" type="success">生效 {{ row.release_effective_at }}</el-text><br />
                <el-text size="small">{{ row.release_title }}</el-text>
              </template>
            </el-table-column>
            <el-table-column label="状态/裁定" width="180">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'PENDING'" type="danger">待法务裁定</el-tag>
                <el-tag v-else type="success">{{ row.ruling === 'HOLD_WINS' ? '挂起方胜诉' : '撤销方胜诉' }}</el-tag>
                <div style="font-size:12px">{{ row.ruling_reason }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button size="small" type="danger" @click="adjudicate(row, 'HOLD_WINS')">维持挂起</el-button>
                  <el-button size="small" type="success" @click="adjudicate(row, 'RELEASE_WINS')">支持撤销</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <el-alert type="info" :closable="false" style="margin-top:10px"
            title="两份冲突依据并列展示、按文书生效时点排序而非录入先后；裁定期间挂起方向保守生效，结论写回台账。" />
        </el-tab-pane>

        <el-tab-pane name="reviews">
          <template #label>
            法务复核
            <el-badge v-if="pendingReviewCount" :value="pendingReviewCount" type="warning" style="margin-left:6px" />
          </template>
          <el-table :data="reviews" border size="small">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="doc_no" label="文书" width="170" />
            <el-table-column prop="asset_code" label="资产" width="140" />
            <el-table-column prop="location_ref" label="位置" width="220" show-overflow-tooltip />
            <el-table-column prop="review_due_at" label="复核截止" width="190" />
            <el-table-column prop="hold_expires_at" label="当前到期日" width="190" />
            <el-table-column label="结论" width="120">
              <template #default="{ row }">
                <el-tag :type="row.decision === 'PENDING' ? 'warning' : 'success'">{{ reviewLabel(row.decision) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="理由" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="280">
              <template #default="{ row }">
                <template v-if="row.decision === 'PENDING'">
                  <el-button size="small" type="warning" @click="openReview(row, 'EXTEND')">延期(新版本)</el-button>
                  <el-button size="small" type="success" @click="openReview(row, 'RESUME')">恢复执行</el-button>
                  <el-button size="small" type="danger" plain @click="openReview(row, 'ESCALATE')">升级</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="季度合规汇总" name="quarter">
          <div style="margin-bottom:10px; display:flex; gap:10px">
            <el-button type="primary" @click="runCycle">跑季度复核周期（自动升级）</el-button>
            <el-button @click="reconcileLedger">台账逐位置对账</el-button>
            <el-button @click="loadQuarter">刷新清单</el-button>
          </div>
          <el-alert type="warning" :closable="false" style="margin-bottom:10px"
            title="季度末交合规负责人：长期挂起、多次延期、依据缺失三类清单；挂起超法定期限又无新依据的自动升级，避免一次保全无限期搁置删除请求。" />
          <el-tabs>
            <el-tab-pane :label="`长期挂起(${quarter.longHolds?.length || 0})`">
              <el-table :data="quarter.longHolds || []" border size="small">
                <el-table-column prop="doc_no" label="文书" width="180" />
                <el-table-column prop="asset_code" label="资产" width="140" />
                <el-table-column prop="location_ref" label="位置" min-width="200" show-overflow-tooltip />
                <el-table-column prop="hold_effective_at" label="挂起生效" width="190" />
                <el-table-column prop="age_duration" label="挂起时长" width="180" />
                <el-table-column prop="status" label="状态" width="110" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane :label="`多次延期(${quarter.multiExtensions?.length || 0})`">
              <el-table :data="quarter.multiExtensions || []" border size="small">
                <el-table-column prop="doc_no" label="文书" width="180" />
                <el-table-column prop="asset_code" label="资产" width="140" />
                <el-table-column prop="location_ref" label="位置" min-width="200" show-overflow-tooltip />
                <el-table-column prop="extension_count" label="延期次数" width="100" />
                <el-table-column prop="status" label="状态" width="110" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane :label="`依据缺失/合规问题(${issues.length})`">
              <el-table :data="issues" border size="small">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column prop="issue_type" label="问题类型" width="220" show-overflow-tooltip />
                <el-table-column prop="detail" label="说明" min-width="280" show-overflow-tooltip />
                <el-table-column prop="created_at" label="生成时间" width="190" />
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="row.status === 'OPEN' ? 'danger' : 'success'">
                      {{ row.status === 'OPEN' ? '待复核' : '已解决' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="120">
                  <template #default="{ row }">
                    <el-button v-if="row.status === 'OPEN'" size="small" type="primary" @click="resolveIssue(row.id)">
                      复核解决
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>

        <el-tab-pane label="时点状态复现" name="asof">
          <el-form inline>
            <el-form-item label="主体哈希">
              <el-input v-model="asOfSubject" style="width:420px" placeholder="subject_hash" />
            </el-form-item>
            <el-form-item label="时点">
              <el-date-picker v-model="asOfAt" type="datetime" style="width:220px"
                value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空=当前" />
            </el-form-item>
            <el-form-item><el-button type="primary" @click="loadAsOf">复现</el-button></el-form-item>
          </el-form>
          <el-alert v-if="asOf" :type="asOf.held ? 'error' : 'success'" :closable="false" style="margin-bottom:10px"
            :title="asOf.held ? `该时点主体处于挂起状态，有效挂起位置 ${asOf.activeEntriesAtPoint.filter((e) => e.effectiveAtPoint).length} 个` : '该时点主体无有效挂起'" />
          <el-table v-if="asOf" :data="asOf.activeEntriesAtPoint" border size="small" style="margin-bottom:14px">
            <el-table-column prop="doc_no" label="依据文书" width="180" />
            <el-table-column prop="doc_version_no" label="版本" width="70" />
            <el-table-column prop="asset_code" label="资产" width="140" />
            <el-table-column prop="location_ref" label="位置" min-width="200" show-overflow-tooltip />
            <el-table-column prop="basis" label="凭什么挂起" min-width="320" show-overflow-tooltip />
            <el-table-column label="该时点是否有效" width="130">
              <template #default="{ row }">
                <el-tag :type="row.effectiveAtPoint ? 'danger' : 'info'">
                  {{ row.effectiveAtPoint ? '有效挂起' : '已过期/未生效' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-timeline v-if="asOf">
            <el-timeline-item v-for="e in asOf.eventsUpToPoint" :key="e.id" :timestamp="e.event_at" placement="top">
              <strong>{{ e.event_type }}</strong>
              <el-tag size="small" style="margin-left:6px">{{ e.actor }}</el-tag>
              <pre class="mono" style="margin:4px 0 0; white-space:pre-wrap">{{ JSON.stringify(e.detail_json, null, 2) }}</pre>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>

        <el-tab-pane label="不可变事件流" name="events">
          <el-form inline>
            <el-form-item label="主体哈希"><el-input v-model="eventSubject" style="width:420px" /></el-form-item>
            <el-form-item label="工单ID"><el-input v-model.number="eventTicketId" style="width:120px" /></el-form-item>
            <el-form-item><el-button @click="loadEvents">查询</el-button></el-form-item>
          </el-form>
          <el-timeline>
            <el-timeline-item v-for="e in events" :key="e.id" :timestamp="e.event_at" placement="top">
              <strong>{{ e.event_type }}</strong>
              <el-tag size="small" style="margin-left:6px">{{ e.actor }}</el-tag>
              <pre class="mono" style="margin:4px 0 0; white-space:pre-wrap">{{ JSON.stringify(e.detail_json, null, 2) }}</pre>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../api/http'
import { ElMessage, ElMessageBox } from 'element-plus'

const docTypes = [
  { value: 'LABOR_DISPUTE', label: '劳动争议' },
  { value: 'LITIGATION_PRESERVATION', label: '诉讼保全' },
  { value: 'REGULATORY_INSPECTION', label: '监管检查' },
  { value: 'OTHER_LEGAL', label: '其他法定文书' }
]
const locationKinds = [
  { value: 'PRIMARY_STORAGE', label: '主存储' },
  { value: 'BACKUP_SNAPSHOT', label: '备份快照' },
  { value: 'LEGAL_RETENTION', label: '法定留存凭证' },
  { value: 'THIRD_PARTY_COPY', label: '第三方副本' }
]
const emptyLocation = () => ({
  assetCode: '', locationRef: '', locationKind: 'BACKUP_SNAPSHOT',
  scopeDetail: '', ownerPerson: '', expiresAt: ''
})
const form = reactive({
  docNo: '', docType: 'LITIGATION_PRESERVATION', title: '', issuingAuthority: '',
  scopeText: '', effectiveAt: '', expiresAt: '', direction: 'HOLD',
  ticketNo: '', locations: [emptyLocation()]
})
const activeTab = ref('entries')
const entries = ref([])
const documents = ref([])
const conflicts = ref([])
const reviews = ref([])
const issues = ref([])
const events = ref([])
const quarter = ref({})
const entryFilter = ref('')
const docNoFilter = ref('')
const asOfSubject = ref('')
const asOfAt = ref('')
const asOf = ref(null)
const eventSubject = ref('')
const eventTicketId = ref(null)

const pendingConflictCount = computed(() => conflicts.value.filter((c) => c.status === 'PENDING').length)
const pendingReviewCount = computed(() => reviews.value.filter((r) => r.decision === 'PENDING').length)

function withOffset(s) {
  if (!s) return null
  return /[+-]\d{2}:?\d{2}$|Z$/.test(s) ? s : s + '+08:00'
}
function addLocation() {
  form.locations.push(emptyLocation())
}
async function register() {
  const body = {
    docNo: form.docNo, docType: form.docType, title: form.title,
    issuingAuthority: form.issuingAuthority || null, scopeText: form.scopeText,
    effectiveAt: withOffset(form.effectiveAt), expiresAt: withOffset(form.expiresAt),
    direction: form.direction, ticketNo: form.ticketNo || null,
    locations: form.locations.map((l) => ({
      assetCode: l.assetCode, locationRef: l.locationRef, locationKind: l.locationKind,
      scopeDetail: l.scopeDetail || null, ownerPerson: l.ownerPerson,
      expiresAt: withOffset(l.expiresAt)
    }))
  }
  const r = (await http.post('/api/holds/register', body)).data
  ElMessage.success(`台账已登记：位置 ${r.entryCount} 个，冲突 ${r.conflictIds.length} 起，工单挂起 ${r.heldTicketIds.length} 张`)
  await Promise.all([loadEntries(), loadConflicts(), loadReviews()])
}
async function loadEntries() {
  entries.value = (await http.get('/api/holds/entries', { params: { status: entryFilter.value || undefined } })).data
}
async function loadDocuments() {
  documents.value = (await http.get('/api/holds/documents', { params: { docNo: docNoFilter.value || undefined } })).data
}
async function loadConflicts() {
  conflicts.value = (await http.get('/api/holds/conflicts')).data
}
async function loadReviews() {
  reviews.value = (await http.get('/api/holds/reviews')).data
}
async function loadIssues() {
  issues.value = (await http.get('/api/holds/compliance-issues')).data
}
async function loadEvents() {
  events.value = (await http.get('/api/holds/events', {
    params: { subjectHash: eventSubject.value || undefined, ticketId: eventTicketId || undefined }
  })).data
}
async function loadQuarter() {
  quarter.value = (await http.get('/api/holds/quarterly-report')).data
  await loadIssues()
}
async function adjudicate(row, ruling) {
  const { value } = await ElMessageBox.prompt('裁定理由（写回台账与事件流）', `法务裁定：${ruling}`, {
    inputType: 'textarea', inputValue: ruling === 'HOLD_WINS' ? '保全范围覆盖该位置，维持挂起' : '保全已撤销，恢复删除义务'
  })
  await http.post(`/api/holds/conflicts/${row.id}/adjudicate`, { ruling, reason: value })
  ElMessage.success('裁定结论已写回台账')
  await Promise.all([loadConflicts(), loadEntries()])
}
async function openReview(row, decision) {
  const body = { decision, reason: '' }
  if (decision === 'EXTEND') {
    const { value } = await ElMessageBox.prompt('填写延期信息，格式：新到期日(YYYY-MM-DDTHH:mm:ss)|理由|新版本文书标题',
      '延期挂起（原件冻结，另生成新版本）', {
        inputType: 'textarea',
        inputValue: '2027-02-13T18:00:00|仲裁程序延期，保全续期|劳动争议仲裁保全裁定书（续期）'
      })
    const [newExpiresAt, reason, newDocTitle] = value.split('|')
    body.newExpiresAt = withOffset(newExpiresAt)
    body.reason = reason
    body.newDocTitle = newDocTitle
  } else {
    const { value } = await ElMessageBox.prompt('复核结论理由', decision, { inputType: 'textarea' })
    body.reason = value
  }
  const r = (await http.post(`/api/holds/reviews/${row.id}/decision`, body)).data
  ElMessage.success(decision === 'RESUME' ? '已恢复执行并重新触发影响面预览' : `复核结论已落账：${r.decision}`)
  await Promise.all([loadReviews(), loadEntries(), loadQuarter()])
}
async function runCycle() {
  const r = (await http.post('/api/holds/review-cycle')).data
  ElMessage.success(`复核周期完成：超期复核 ${r.cycle.overdueReviews}、到期未恢复 ${r.cycle.expiredEntries}、自动升级 ${r.cycle.escalatedEntries}`)
  await loadQuarter()
}
async function reconcileLedger() {
  const r = (await http.post('/api/holds/reconcile')).data
  ElMessage.warning(`对账完成，未决合规问题 ${r.openIssues} 个`)
  await loadIssues()
}
async function resolveIssue(id) {
  const { value } = await ElMessageBox.prompt('复核说明', '解决合规问题', { inputType: 'textarea' })
  await http.post(`/api/holds/compliance-issues/${id}/resolve`, { note: value })
  ElMessage.success('合规问题已解决')
  await loadIssues()
}
async function loadAsOf() {
  asOf.value = (await http.get('/api/holds/status-as-of', {
    params: { subjectHash: asOfSubject.value, at: withOffset(asOfAt.value) || undefined }
  })).data
}
function entryStatusLabel(s) {
  return { ACTIVE: '生效中', RELEASED: '已恢复/关闭', EXPIRED: '已到期', ESCALATED: '已升级' }[s] || s
}
function entryStatusType(s) {
  return { ACTIVE: 'danger', RELEASED: 'success', EXPIRED: 'info', ESCALATED: 'warning' }[s] || ''
}
function adjudicationLabel(s) {
  return { PENDING: '待裁定', HOLD_UPHELD: '维持挂起', RELEASE_UPHELD: '支持撤销' }[s] || s
}
function kindLabel(k) {
  return locationKinds.find((x) => x.value === k)?.label || k
}
function reviewLabel(d) {
  return { PENDING: '待复核', EXTEND: '已延期', RESUME: '恢复执行', ESCALATE: '已升级' }[d] || d
}
onMounted(() => {
  loadEntries()
  loadConflicts()
  loadReviews()
  loadIssues()
})
</script>

<style scoped>
.loc-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
</style>
