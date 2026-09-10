<template>
  <main class="login-page">
    <section class="brand-panel">
      <img class="hero-art" src="@/assets/login/risk-intelligence-hero.webp" alt="" width="1440" height="921" />
      <div class="brand-shade"></div>
      <div class="brand-content">
        <div class="brand-mark"><span class="mark-core"></span>DataGraph Bank</div>
        <div class="hero-copy">
          <span class="eyebrow">BANKING RISK INTELLIGENCE</span>
          <h1>银行案例识别与图谱分析平台</h1>
          <p>贯通案例上传、可疑报告生成、框架抽取、相似度匹配、图谱分析以及案例复核审批。</p>
        </div>

        <div class="capability-grid">
          <article v-for="item in capabilities" :key="item.title">
            <el-icon><component :is="item.icon" /></el-icon>
            <div><strong>{{ item.title }}</strong><span>{{ item.description }}</span></div>
          </article>
        </div>

        <div class="topology-card">
          <div class="topology-heading">
            <div><span>银行端业务与技术拓扑</span><small>从原始数据到风险研判与监管协同</small></div>
            <span class="live-status"><i></i>核心能力拓扑</span>
          </div>
          <div class="topology-flow">
            <div class="topology-node"><el-icon><UploadFilled /></el-icon><span>数据批次接入</span></div>
            <i class="flow-line"></i>
            <div class="topology-node featured"><el-icon><Cpu /></el-icon><span>算法与 Worker</span></div>
            <i class="flow-line"></i>
            <div class="topology-node"><el-icon><Warning /></el-icon><span>存疑交易与案例</span></div>
            <i class="flow-line"></i>
            <div class="topology-node"><el-icon><Share /></el-icon><span>事件与事理图谱</span></div>
            <i class="flow-line"></i>
            <div class="topology-node"><el-icon><DocumentChecked /></el-icon><span>线索研判与监管</span></div>
          </div>
          <p class="topology-note">Vue 业务门户 · Spring Boot 业务服务 · Python 识别与推理 Worker · PostgreSQL / Redis / Kafka / MinIO · TuGraph 主图</p>
        </div>
      </div>
    </section>

    <section class="login-panel">
      <div class="mobile-brand"><span class="mark-core"></span>DataGraph Bank</div>
      <div class="login-card">
        <div class="login-heading">
          <span class="welcome-label">欢迎使用</span>
          <h2>{{ registerMode ? '创建访问账号' : '登录业务工作台' }}</h2>
          <p>{{ registerMode ? '注册后即可查看系统中的共享案例列表' : '请输入系统账号完成身份验证' }}</p>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" class="login-form" label-position="top">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" size="large" autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="registerMode" label="昵称" prop="nickname">
            <el-input v-model="form.nickname" placeholder="选填" size="large" autocomplete="nickname">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="registerMode" label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="选填，用于账号联系" size="large" autocomplete="email">
              <template #prefix><el-icon><Message /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入密码"
              size="large"
              :autocomplete="registerMode ? 'new-password' : 'current-password'"
              @keyup.enter="handleSubmit"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="registerMode" label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" show-password placeholder="请再次输入密码" size="large" autocomplete="new-password" @keyup.enter="handleSubmit">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <div class="security-tip"><el-icon><CircleCheck /></el-icon>{{ registerMode ? '注册账号为只读账号，不能修改或删除案例' : '账号权限与数据范围由系统统一管理' }}</div>
          <el-button type="primary" size="large" class="login-btn" :loading="submitting" @click="handleSubmit">
            {{ registerMode ? '注册账号' : '登录系统' }}<el-icon class="button-arrow"><Right /></el-icon>
          </el-button>
          <el-button link type="primary" class="mode-switch" @click="switchMode">
            {{ registerMode ? '已有账号？返回登录' : '没有账号？立即注册' }}
          </el-button>
        </el-form>

        <div class="role-summary">
          <span>统一门户支持</span>
          <div><i>超</i>平台管理</div>
          <div><i>银</i>银行业务</div>
          <div><i>监</i>监管协同</div>
        </div>
      </div>
      <footer>
        <span>DataGraph Bank</span>
        <span>安全 · 可追溯 · 事件与事理协同</span>
      </footer>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { registerApi } from '@/api/auth'
import { ElMessage } from 'element-plus'
import {
  CircleCheck, Connection, Cpu, DataAnalysis, DocumentChecked,
  Lock, Message, Right, Share, UploadFilled, User, Warning
} from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const submitting = ref(false)
const registerMode = ref(false)
const form = reactive({ username: '', password: '', confirmPassword: '', nickname: '', email: '' })

const capabilities = [
  { icon: DataAnalysis, title: '结构化案例识别', description: '可疑报告、框架抽取与相似案例匹配' },
  { icon: DocumentChecked, title: '案例复核审批', description: '案例列表、复核、审批和处理留痕' },
  { icon: Connection, title: '线索与风险分析', description: '基于事件图谱和事理图谱开展跨案研判' }
]

const rules = computed(() => ({
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  nickname: [],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  confirmPassword: registerMode.value ? [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: (_rule:any, value:string, callback:(error?:Error)=>void) => value === form.password ? callback() : callback(new Error('两次输入的密码不一致')), trigger: 'blur' }
  ] : []
}))

const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (registerMode.value) {
      await registerApi({ username: form.username.trim(), password: form.password, nickname: form.nickname.trim() || undefined, email: form.email.trim() || undefined })
      ElMessage.success('注册成功，请登录')
      registerMode.value = false
      form.password = ''
      form.confirmPassword = ''
      return
    }
    await userStore.login(form.username.trim(), form.password)
    ElMessage.success('登录成功')
    router.push(userStore.roleCode === 'viewer' ? '/case/list' : '/overview')
  } catch (error:any) {
    ElMessage.error(error?.message || (registerMode.value ? '注册失败' : '用户名或密码错误'))
  } finally {
    submitting.value = false
  }
}
const switchMode = () => {
  registerMode.value = !registerMode.value
  form.password = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}
</script>

<style scoped>
.login-page{min-height:100vh;display:grid;grid-template-columns:minmax(620px,1.45fr) minmax(420px,.75fr);background:#f4f7fb;color:#13213c}
.brand-panel{position:relative;min-height:100vh;overflow:hidden;background:#061a3a}
.hero-art,.brand-shade{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.hero-art{opacity:.72}.brand-shade{background:linear-gradient(90deg,rgba(3,16,42,.94) 0%,rgba(4,21,53,.72) 48%,rgba(4,25,62,.18) 100%),linear-gradient(0deg,rgba(3,16,42,.9) 0%,transparent 55%)}
.brand-content{position:relative;z-index:1;min-height:100%;box-sizing:border-box;padding:46px clamp(42px,6vw,88px);display:flex;flex-direction:column;color:#fff}
.brand-mark,.mobile-brand{display:flex;align-items:center;gap:12px;font-weight:700;letter-spacing:.04em}.mark-core{width:18px;height:18px;border-radius:5px;background:linear-gradient(135deg,#67e8f9,#2563eb);box-shadow:0 0 0 6px rgba(56,189,248,.12),0 0 28px rgba(56,189,248,.55);transform:rotate(45deg)}
.hero-copy{margin-top:auto;max-width:700px}.eyebrow{font-size:12px;letter-spacing:.22em;color:#7dd3fc;font-weight:700}.hero-copy h1{font-size:clamp(36px,4.2vw,62px);line-height:1.12;margin:16px 0 18px;letter-spacing:-.035em}.hero-copy p{max-width:650px;font-size:16px;line-height:1.8;color:#c7d8f5;margin:0}
.capability-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin:34px 0 22px}.capability-grid article{display:flex;gap:12px;align-items:flex-start;padding:16px;border:1px solid rgba(148,190,255,.2);border-radius:14px;background:rgba(11,42,86,.46);backdrop-filter:blur(12px)}.capability-grid .el-icon{font-size:22px;color:#67e8f9;margin-top:2px}.capability-grid strong,.capability-grid span{display:block}.capability-grid strong{font-size:14px;margin-bottom:6px}.capability-grid span{font-size:12px;line-height:1.5;color:#a9bfdf}
.topology-card{padding:18px 20px;border:1px solid rgba(148,190,255,.22);border-radius:16px;background:rgba(5,27,65,.72);backdrop-filter:blur(14px)}.topology-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:17px}.topology-heading span,.topology-heading small{display:block}.topology-heading span{font-size:14px;font-weight:700}.topology-heading small{margin-top:4px;color:#829fc7;font-size:11px}.live-status{font-size:11px!important;color:#8ee7bf!important;font-weight:500!important}.live-status i{display:inline-block;width:6px;height:6px;margin-right:6px;border-radius:50%;background:#34d399;box-shadow:0 0 10px #34d399}
.topology-flow{display:flex;align-items:center}.topology-node{min-width:94px;padding:12px 7px;text-align:center;border-radius:10px;background:#102f62;border:1px solid #28518a}.topology-node.featured{background:linear-gradient(135deg,#124a84,#1d4ed8);border-color:#60a5fa}.topology-node .el-icon{display:block;margin:0 auto 7px;font-size:19px;color:#7dd3fc}.topology-node span{font-size:11px}.flow-line{height:1px;flex:1;min-width:10px;background:linear-gradient(90deg,#3b82f6,#67e8f9);position:relative}.flow-line:after{content:"";position:absolute;right:-1px;top:-3px;border-left:5px solid #67e8f9;border-top:3px solid transparent;border-bottom:3px solid transparent}.topology-note{margin:13px 0 0;font-size:10px;color:#7896bf;text-align:center}
.login-panel{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:50px clamp(34px,5vw,76px);box-sizing:border-box;background:radial-gradient(circle at 90% 5%,#e0ecff 0,transparent 32%),#f8fafc}.login-card{width:100%;max-width:440px}.mobile-brand{display:none}.welcome-label{display:inline-block;padding:5px 10px;border-radius:20px;background:#e8f1ff;color:#2563eb;font-size:12px;font-weight:700}.login-heading h2{font-size:30px;margin:16px 0 9px;color:#102143}.login-heading p{margin:0;color:#7a879d;font-size:14px}.login-form{margin-top:38px}.login-form :deep(.el-form-item){margin-bottom:23px}.login-form :deep(.el-form-item__label){font-weight:600;color:#34425a;padding-bottom:9px}.login-form :deep(.el-input__wrapper){height:50px;border-radius:10px;box-shadow:0 0 0 1px #d7dfeb inset;background:#fff}.login-form :deep(.el-input__wrapper.is-focus){box-shadow:0 0 0 1px #2563eb inset,0 0 0 4px rgba(37,99,235,.08)}.security-tip{display:flex;align-items:center;gap:7px;margin:-5px 0 24px;color:#8491a5;font-size:12px}.security-tip .el-icon{color:#3b82f6}.login-btn{width:100%;height:52px;border-radius:10px;font-weight:700;font-size:15px;background:linear-gradient(90deg,#174ea6,#2563eb);box-shadow:0 10px 24px rgba(37,99,235,.22)}.button-arrow{margin-left:8px}
.mode-switch{display:block;margin:14px auto 0}.role-summary{margin-top:34px;padding-top:22px;border-top:1px solid #e4e9f1;display:flex;align-items:center;gap:15px;flex-wrap:wrap;color:#7c899e;font-size:11px}.role-summary>span{width:100%;font-weight:600;color:#58677e}.role-summary div{display:flex;align-items:center;gap:6px}.role-summary i{font-style:normal;width:23px;height:23px;display:grid;place-items:center;border-radius:7px;background:#e8f1ff;color:#2563eb;font-weight:700}
footer{width:100%;max-width:440px;margin-top:auto;padding-top:42px;display:flex;justify-content:space-between;color:#9aa6b8;font-size:11px}
@media(max-width:1100px){.login-page{grid-template-columns:1.15fr .85fr}.capability-grid{grid-template-columns:1fr}.capability-grid article:nth-child(n+3){display:none}.topology-card{display:none}}
@media(max-width:820px){.login-page{display:block}.brand-panel{display:none}.login-panel{min-height:100vh;padding:32px 24px}.mobile-brand{display:flex;width:100%;max-width:440px;margin-bottom:auto}.login-card{margin:50px 0}.login-heading h2{font-size:27px}footer{margin-top:auto}}
</style>
