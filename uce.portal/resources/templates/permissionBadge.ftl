<style>
    .permission-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 32px;
        height: 32px;
        margin-left: 8px;
        border-radius: 50%;
        background: #e5e7eb;
        color: #374151;
        font-size: 20px;
        cursor: help;
    }
    .permission-badge.read {
        background: #38a169;
        color: #ffffff;
    }
    .permission-badge.write {
        background: #2563eb;
        color: #ffffff;
    }
    .permission-badge.owner {
        background: #f59e0b;
        color: #ffffff;
    }
    .permission-badge.admin {
        background: #dc2626;
        color: #ffffff;
    }
</style>

<#assign levelText = effectivePermission.level()?string>
<span class="permission-badge ${levelText?lower_case}"
      title="${levelText?switch(
        'READ','Leseberechtigung',
        'WRITE','Schreibberechtigung',
        'OWNER','Besitzerrechte',
        'ADMIN','Administratorrechte',
        'NONE','Keine Berechtigung',
        'Unbekannt'
      )}">
    🛡
</span>
