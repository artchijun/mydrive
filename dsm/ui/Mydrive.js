/**
 * Mydrive — DSM 기본 메뉴의 Mydrive 창.
 * 시놀로지 드라이브 관리 콘솔처럼 DSM 안의 창으로 열리고, 그 안에 Mydrive 관리 콘솔(http://<NAS>:8642/admin)을 보여 줌.
 * DSM 7.2 는 외부 주소를 DSM 창으로 여는 예전 방식(type: legacy)을 막아서, DSM 창(ExtJS)을 직접 만들고 iframe 을 넣음.
 * DSM 7 은 Ext JS 3 — new Ext.XXX(cfg) / xtype 방식.
 */
Ext.ns("SYNO.SDS.Mydrive");

Ext.define("SYNO.SDS.Mydrive.Application", {
    extend: "SYNO.SDS.AppInstance",
    appWindowName: "SYNO.SDS.Mydrive.AppWindow",
    constructor: function () { this.callParent(arguments); }
});

Ext.define("SYNO.SDS.Mydrive.AppWindow", {
    extend: "SYNO.SDS.AppWindow",

    constructor: function (cfg) {
        var host = window.location.hostname;
        var src = "http://" + host + ":8642/admin?embed=1";
        var html;
        if (window.location.protocol === "https:") {
            // https 로 연 DSM 안에는 http 페이지를 넣을 수 없음(브라우저가 막음) → 새 탭으로
            html = '<div style="padding:28px;font:14px/1.6 sans-serif;color:#333">' +
                   'DSM을 https(보안 연결)로 열면 Mydrive 관리 콘솔(http)을 이 창 안에 보여 줄 수 없습니다.<br><br>' +
                   '<a href="' + src + '" target="_blank" rel="noopener" style="font-weight:bold">새 탭에서 Mydrive 관리 콘솔 열기</a>' +
                   '<br><br><span style="color:#888">DSM을 http(5000 포트)로 열면 이 창 안에서 바로 보입니다.</span></div>';
        } else {
            html = '<iframe src="' + src + '" style="width:100%;height:100%;border:0;display:block;background:#121418" ' +
                   'allow="clipboard-write" referrerpolicy="no-referrer"></iframe>';
        }
        cfg = Ext.apply({
            title: "Mydrive",
            width: 1120,
            height: 740,
            minWidth: 760,
            minHeight: 480,
            resizable: true,
            maximizable: true,
            minimizable: true,
            layout: "fit",
            items: [{ xtype: "panel", border: false, bodyStyle: "background:#121418", html: html }]
        }, cfg || {});
        this.callParent([cfg]);
    }
});
