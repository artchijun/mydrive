package kr.artchijun.mydrive

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 구글 드라이브 붙이기 (화면 없음): 휴대폰의 구글 계정 고르기 → 안드로이드가 허용 화면을 띄움 → 토큰 확인 → 계정 저장.
 *   파일관리자+ 같은 앱과 같은 방식. 브라우저·비밀번호 입력 없음 (휴대폰에 로그인된 계정을 씀).
 *   이 앱의 패키지(kr.artchijun.mydrive)와 서명(SHA-1)이 구글 클라우드 프로젝트 'Mydrive' 의 Android 클라이언트로 등록돼 있어야 함.
 */
class CloudConnectActivity : Activity() {
    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, CloudConnectActivity::class.java))
        private const val PICK = 1
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b != null) return
        // 계정 고르기 (고른 계정만 이 앱에 보이게 됨 — 연락처·계정 권한 필요 없음)
        val i = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), "Google Drive 에 연결할 계정을 고르세요", null, null, null)
        try { startActivityForResult(i, PICK) } catch (e: Exception) { done("이 휴대폰에서 구글 계정을 고를 수 없습니다") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req != PICK) return
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (res != RESULT_OK || name.isNullOrEmpty()) { done(null); return }
        val acct = Account(name, data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: "com.google")
        // 허용 화면은 안드로이드(구글 플레이 서비스)가 띄움 → 끝나면 토큰
        AccountManager.get(this).getAuthToken(acct, GDrive.SCOPE, null, this, { f ->
            val err = try {
                val r = f.result
                if (r.getString(AccountManager.KEY_AUTHTOKEN).isNullOrEmpty()) "허용하지 않았습니다" else null
            } catch (e: android.accounts.OperationCanceledException) { "허용하지 않았습니다" }
            catch (e: Exception) { "구글 계정 연결 실패: " + (e.message ?: e.javaClass.simpleName) }
            if (err != null) { done(err); return@getAuthToken }
            val d = GDrive.addDeviceAccount(this, name)
            Thread {   // 실제로 드라이브를 읽을 수 있는지 한 번 확인
                val chk = try { d.list(""); null } catch (e: Exception) { e.message ?: "드라이브를 읽지 못했습니다" }
                runOnUiThread { if (chk != null) { d.disconnect(); done(chk) } else done("Google Drive 연결됨 · $name") }
            }.start()
        }, null)
    }

    private fun done(msg: String?) {
        if (msg != null) Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        Prefs(this).putInt("cloudStamp", Prefs(this).int("cloudStamp", 0) + 1)
        finish()
    }
}
