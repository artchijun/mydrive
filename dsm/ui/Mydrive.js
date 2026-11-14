/**
 * Mydrive — DSM 기본 메뉴의 Mydrive 창.
 * 시놀로지 드라이브 관리 콘솔처럼 DSM 안의 창으로 열리고, 그 안에 Mydrive 관리 콘솔을 보여 줌.
 * DSM 7.2 는 외부 주소를 DSM 창으로 여는 예전 방식(type: legacy)을 막아서, DSM 창(ExtJS)을 직접 만들고 iframe 을 넣음.
 * 콘솔은 DSM 주소 그대로(같은 패키지의 md.cgi 중계)로 불러옴 → https·QuickConnect 로 DSM 을 열어도 보임.
 *   (예전: http://<NAS>:8642/admin 을 바로 넣음 → 브라우저가 다른 포트를 막아 창이 검게 나옴)
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
        var src = "/webman/3rdparty/Mydrive/md.cgi?u=" + encodeURIComponent("/admin?embed=1");
        var html = '<iframe src="' + src + '" style="width:100%;height:100%;border:0;display:block;background:#121418" ' +
                   'allow="clipboard-write"></iframe>';
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
