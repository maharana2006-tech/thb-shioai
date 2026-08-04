' =============================================================================
' ValidateAll.bas  — order-import template macro (revised Sprint 48)
' =============================================================================
' Two-phase validation triggered from the "Validate Data" button on the
' Import sheet.
'
'   Phase 1 — LOCAL checks (fast, no network):
'     * Required fields present on the leader row of each orderRef group.
'     * billTo = SENDER / RECIPIENT / THIRD_PARTY (case-insensitive).
'     * If billTo != THIRD_PARTY: accountNumber must NOT be blank.
'     * International shipments (countryCode <> US, heuristic) must have
'       at least one row in the group with full customs data
'       (itemDescription + hsCode + countryOfOrigin + itemQuantity +
'        itemUnitValue).
'     * HS code shape (4-10 digits, optional dots).
'     * countryOfOrigin ISO-2 letters.
'     * itemQuantity positive integer.
'
'   Phase 2 — BACKEND re-validation (network):
'     Serialises every filled row to JSON and POSTs to
'     /api/v1/orders/import/validate. Merges the returned rows' errors +
'     warnings back onto the sheet.
'
' Colour coding:
'   Red fill (RGB 255,204,204)      — hard error, backend WILL reject.
'   Yellow fill (RGB 255,243,204)   — warning, backend accepts but recommend fix.
'   Green fill (RGB 220,255,220)    — row previously flagged is now clean.
'   (No fill)                       — never touched.
'
' Configuration (workbook-wide constants in the module):
'   BACKEND_BASE_URL  — points at your backend (e.g. http://localhost:8080).
'   BEARER_PROMPT     — when TRUE, prompt for a Bearer token on first call
'                       per session. Cached in a Static in this module.
'
' How to inject: Alt+F11 → File → Import File → pick this .bas.
' Assign the macro to a Form Button labelled "Validate Data" on the Import sheet.
' =============================================================================

Option Explicit

Private Const BACKEND_BASE_URL As String = "http://localhost:8080"
Private Const BEARER_PROMPT As Boolean = True

Private Const COLOR_ERROR As Long = &HCCCCFF     ' RGB 255,204,204 (red)
Private Const COLOR_WARN  As Long = &HCCF3FF     ' RGB 255,243,204 (yellow)
Private Const COLOR_OK    As Long = &HDCFFDC     ' RGB 220,255,220 (green)

' Session-cached token so the operator doesn't get prompted per row.
Private cachedBearer As String

Public Sub ValidateAll()
    Dim src As Worksheet
    On Error Resume Next
    Set src = ThisWorkbook.Worksheets("Import")
    On Error GoTo 0
    If src Is Nothing Then
        MsgBox "No sheet named 'Import' — nothing to validate.", vbExclamation, "ValidateAll"
        Exit Sub
    End If

    Dim lastRow As Long, lastCol As Long
    lastCol = src.Cells(1, src.Columns.Count).End(xlToLeft).Column
    lastRow = src.Cells(src.Rows.Count, 1).End(xlUp).Row
    If lastRow < 2 Then
        MsgBox "Nothing to validate — no data rows.", vbInformation, "ValidateAll"
        Exit Sub
    End If

    ' Header index — build once so per-cell lookups are cheap.
    Dim headers As Object
    Set headers = CreateObject("Scripting.Dictionary")
    headers.CompareMode = 1
    Dim c As Long
    For c = 1 To lastCol
        headers(CStr(src.Cells(1, c).Value)) = c
    Next c

    ' Clear previous fills.
    src.Range(src.Cells(2, 1), src.Cells(lastRow, lastCol)).Interior.ColorIndex = xlNone

    ' ---- Phase 1: local checks ----
    Dim errorCount As Long, warnCount As Long
    Dim currentOrderRef As String
    currentOrderRef = ""
    Dim r As Long
    For r = 2 To lastRow
        Dim orderRef As String
        orderRef = SafeCell(src, r, headers, "orderRef")
        Dim isLeader As Boolean
        isLeader = (Len(orderRef) = 0) Or (orderRef <> currentOrderRef)
        If Len(orderRef) > 0 Then currentOrderRef = orderRef
        If isLeader Then
            ValidateLeaderRow src, r, headers, errorCount
        End If
        ValidateItemCells src, r, headers, errorCount, warnCount
    Next r
    ' International check runs at the group level after per-row locals.
    ValidateInternationalGroups src, lastRow, headers, errorCount

    ' ---- Phase 2: backend re-validation ----
    Dim backendMsg As String
    backendMsg = CallBackendValidate(src, headers, lastRow, lastCol, errorCount, warnCount)

    Dim summary As String
    summary = "Local: " & errorCount & " error(s), " & warnCount & " warning(s)." & vbCrLf
    If Len(backendMsg) > 0 Then summary = summary & backendMsg
    MsgBox summary, vbInformation, "ValidateAll"
End Sub

' ---- Phase 1 helpers ----

Private Sub ValidateLeaderRow(ByRef src As Worksheet, ByVal r As Long, _
                               ByRef headers As Object, ByRef errorCount As Long)
    If MarkIfBlank(src, r, headers, "clientCode") Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "recipientName") Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "addressLine1") Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "city") Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "postalCode") Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "countryCode") Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "carrierCode") Then errorCount = errorCount + 1
    Dim weightV As Variant
    weightV = RawCell(src, r, headers, "weight")
    If Not IsNumeric(weightV) Or Val(CStr(weightV)) <= 0 Then
        MarkCell src, r, headers, "weight", COLOR_ERROR
        errorCount = errorCount + 1
    End If
    ' billTo = THIRD_PARTY requires accountNumber to be present; otherwise
    ' the account cell is still a mandatory pick from the dropdown.
    Dim billTo As String
    billTo = UCase$(SafeCell(src, r, headers, "billTo"))
    If Len(billTo) > 0 And billTo <> "SENDER" And billTo <> "RECIPIENT" And billTo <> "THIRD_PARTY" Then
        MarkCell src, r, headers, "billTo", COLOR_ERROR
        errorCount = errorCount + 1
    End If
    If MarkIfBlank(src, r, headers, "accountNumber") Then errorCount = errorCount + 1
End Sub

Private Sub ValidateItemCells(ByRef src As Worksheet, ByVal r As Long, _
                               ByRef headers As Object, ByRef errorCount As Long, _
                               ByRef warnCount As Long)
    Dim hs As String
    hs = SafeCell(src, r, headers, "hsCode")
    If Len(hs) > 0 And Not LikeHsCode(hs) Then
        MarkCell src, r, headers, "hsCode", COLOR_WARN
        warnCount = warnCount + 1
    End If
    Dim coo As String
    coo = SafeCell(src, r, headers, "countryOfOrigin")
    If Len(coo) > 0 And (Len(coo) <> 2 Or Not IsAlpha(coo)) Then
        MarkCell src, r, headers, "countryOfOrigin", COLOR_ERROR
        errorCount = errorCount + 1
    End If
    Dim qty As Variant
    qty = RawCell(src, r, headers, "itemQuantity")
    If Not IsEmpty(qty) And Len(CStr(qty)) > 0 Then
        If Not IsNumeric(qty) Or Val(CStr(qty)) <= 0 Or (Val(CStr(qty)) <> Int(Val(CStr(qty)))) Then
            MarkCell src, r, headers, "itemQuantity", COLOR_ERROR
            errorCount = errorCount + 1
        End If
    End If
End Sub

' Group by orderRef; for each group where leader.countryCode != US, ensure at
' least one row has FULL customs data. Fires red on the leader when not.
Private Sub ValidateInternationalGroups(ByRef src As Worksheet, ByVal lastRow As Long, _
                                         ByRef headers As Object, ByRef errorCount As Long)
    Dim groups As Object
    Set groups = CreateObject("Scripting.Dictionary")
    groups.CompareMode = 1
    Dim r As Long
    For r = 2 To lastRow
        Dim key As String
        key = SafeCell(src, r, headers, "orderRef")
        If Len(key) = 0 Then key = "__row_" & r
        Dim list As String
        If groups.Exists(key) Then list = groups(key) Else list = ""
        If Len(list) > 0 Then list = list & ","
        list = list & r
        groups(key) = list
    Next r

    Dim k As Variant
    For Each k In groups.Keys
        Dim rowsInGroup As Variant
        rowsInGroup = Split(groups(k), ",")
        Dim leaderRow As Long
        leaderRow = CLng(rowsInGroup(0))
        Dim country As String
        country = UCase$(SafeCell(src, leaderRow, headers, "countryCode"))
        If Len(country) = 0 Or country = "US" Then GoTo NextGroup
        Dim hasFull As Boolean
        hasFull = False
        Dim i As Long
        For i = 0 To UBound(rowsInGroup)
            Dim gr As Long
            gr = CLng(rowsInGroup(i))
            If Len(SafeCell(src, gr, headers, "itemDescription")) > 0 _
                And Len(SafeCell(src, gr, headers, "hsCode")) > 0 _
                And Len(SafeCell(src, gr, headers, "countryOfOrigin")) > 0 _
                And Len(SafeCell(src, gr, headers, "itemQuantity")) > 0 _
                And Len(SafeCell(src, gr, headers, "itemUnitValue")) > 0 Then
                hasFull = True
                Exit For
            End If
        Next i
        If Not hasFull Then
            MarkCell src, leaderRow, headers, "countryCode", COLOR_ERROR
            MarkCell src, leaderRow, headers, "itemDescription", COLOR_ERROR
            errorCount = errorCount + 1
        End If
NextGroup:
    Next k
End Sub

' ---- Phase 2: backend re-validation ----

Private Function CallBackendValidate(ByRef src As Worksheet, ByRef headers As Object, _
                                      ByVal lastRow As Long, ByVal lastCol As Long, _
                                      ByRef errorCount As Long, ByRef warnCount As Long) As String
    Dim token As String
    token = ResolveBearer()
    If Len(token) = 0 Then
        CallBackendValidate = "Backend re-validation skipped (no Bearer token supplied)."
        Exit Function
    End If

    Dim jsonBody As String
    jsonBody = RowsToJson(src, headers, lastRow, lastCol)

    Dim http As Object
    Set http = CreateObject("MSXML2.ServerXMLHTTP.6.0")
    http.Open "POST", BACKEND_BASE_URL & "/api/v1/orders/import/validate", False
    http.setRequestHeader "Content-Type", "application/json"
    http.setRequestHeader "Authorization", "Bearer " & token
    On Error Resume Next
    http.send jsonBody
    Dim errNum As Long: errNum = Err.Number
    On Error GoTo 0
    If errNum <> 0 Then
        CallBackendValidate = "Backend call failed (network / auth): " & errNum
        Exit Function
    End If
    If http.Status < 200 Or http.Status >= 300 Then
        CallBackendValidate = "Backend returned HTTP " & http.Status & ": " & Left(http.responseText, 200)
        Exit Function
    End If

    ' Response is JSON: { data: { rows: [ { rowNumber, errors, warnings } ] } }
    ' Cheap parse — regex the row entries. Full JSON parse would need a helper
    ' library; keeping this dependency-free.
    Dim backendErrors As Long, backendWarnings As Long
    Dim response As String
    response = http.responseText
    Dim matches As Object, m As Object
    Set matches = MatchAll(response, """rowNumber""\s*:\s*(\d+)\s*,\s*""errors""\s*:\s*\[([^\]]*)\]\s*,\s*""warnings""\s*:\s*\[([^\]]*)\]")
    For Each m In matches
        Dim rowNum As Long
        rowNum = CLng(m.SubMatches(0))
        Dim errArr As String, warnArr As String
        errArr = m.SubMatches(1)
        warnArr = m.SubMatches(2)
        Dim sheetRow As Long
        sheetRow = rowNum + 1 ' rowNumber in preview is 1-based over data rows
        If Len(Trim(errArr)) > 0 Then
            src.Cells(sheetRow, 1).Interior.Color = COLOR_ERROR
            backendErrors = backendErrors + 1
        ElseIf Len(Trim(warnArr)) > 0 Then
            src.Cells(sheetRow, 1).Interior.Color = COLOR_WARN
            backendWarnings = backendWarnings + 1
        End If
    Next m
    errorCount = errorCount + backendErrors
    warnCount = warnCount + backendWarnings
    CallBackendValidate = "Backend: " & backendErrors & " error(s), " & backendWarnings & " warning(s)."
End Function

Private Function ResolveBearer() As String
    If Len(cachedBearer) > 0 Then
        ResolveBearer = cachedBearer
        Exit Function
    End If
    If Not BEARER_PROMPT Then
        ResolveBearer = ""
        Exit Function
    End If
    Dim t As String
    t = InputBox("Paste your Bearer token (from browser localStorage 'multiship_token'):", _
                 "Backend token")
    cachedBearer = t
    ResolveBearer = t
End Function

' Serialise every non-blank row from row 2..lastRow to a JSON array. Header
' names map 1:1 to backend OrderImportRowDTO field names.
Private Function RowsToJson(ByRef src As Worksheet, ByRef headers As Object, _
                             ByVal lastRow As Long, ByVal lastCol As Long) As String
    Dim buf As String
    buf = "["
    Dim r As Long
    Dim first As Boolean: first = True
    For r = 2 To lastRow
        If IsRowBlank(src, r, lastCol) Then GoTo NextRow
        If Not first Then buf = buf & ","
        buf = buf & "{""rowNumber"":" & (r - 1)
        Dim c As Long
        For c = 1 To lastCol
            Dim name As String
            name = CStr(src.Cells(1, c).Value)
            Dim v As String
            v = CStr(src.Cells(r, c).Value)
            If Len(v) > 0 Then
                buf = buf & ",""" & name & """:""" & EscapeJson(v) & """"
            End If
        Next c
        buf = buf & "}"
        first = False
NextRow:
    Next r
    buf = buf & "]"
    RowsToJson = buf
End Function

Private Function IsRowBlank(ByRef src As Worksheet, ByVal r As Long, ByVal lastCol As Long) As Boolean
    Dim c As Long
    For c = 1 To lastCol
        If Len(CStr(src.Cells(r, c).Value)) > 0 Then
            IsRowBlank = False
            Exit Function
        End If
    Next c
    IsRowBlank = True
End Function

Private Function EscapeJson(ByVal s As String) As String
    Dim r As String
    r = Replace(s, "\", "\\")
    r = Replace(r, """", "\""")
    r = Replace(r, vbCr, "\r")
    r = Replace(r, vbLf, "\n")
    r = Replace(r, vbTab, "\t")
    EscapeJson = r
End Function

Private Function MatchAll(ByVal source As String, ByVal pattern As String) As Object
    Dim re As Object
    Set re = CreateObject("VBScript.RegExp")
    re.Pattern = pattern
    re.Global = True
    re.IgnoreCase = False
    Set MatchAll = re.Execute(source)
End Function

' ---- shared helpers ----

Private Function SafeCell(ByRef src As Worksheet, ByVal r As Long, _
                           ByRef headers As Object, ByVal name As String) As String
    If Not headers.Exists(name) Then
        SafeCell = ""
    Else
        SafeCell = Trim(CStr(src.Cells(r, headers(name)).Value))
    End If
End Function

Private Function RawCell(ByRef src As Worksheet, ByVal r As Long, _
                          ByRef headers As Object, ByVal name As String) As Variant
    If Not headers.Exists(name) Then
        RawCell = Empty
    Else
        RawCell = src.Cells(r, headers(name)).Value
    End If
End Function

Private Sub MarkCell(ByRef src As Worksheet, ByVal r As Long, _
                      ByRef headers As Object, ByVal name As String, ByVal color As Long)
    If Not headers.Exists(name) Then Exit Sub
    src.Cells(r, headers(name)).Interior.Color = color
End Sub

Private Function MarkIfBlank(ByRef src As Worksheet, ByVal r As Long, _
                              ByRef headers As Object, ByVal name As String) As Boolean
    If Len(SafeCell(src, r, headers, name)) = 0 Then
        MarkCell src, r, headers, name, COLOR_ERROR
        MarkIfBlank = True
    Else
        MarkIfBlank = False
    End If
End Function

Private Function LikeHsCode(ByVal s As String) As Boolean
    Dim digits As String
    Dim i As Long, ch As String
    For i = 1 To Len(s)
        ch = Mid$(s, i, 1)
        If ch >= "0" And ch <= "9" Then
            digits = digits & ch
        ElseIf ch = "." Then
            ' allowed
        Else
            LikeHsCode = False
            Exit Function
        End If
    Next i
    LikeHsCode = (Len(digits) >= 4 And Len(digits) <= 10)
End Function

Private Function IsAlpha(ByVal s As String) As Boolean
    Dim i As Long, ch As String
    For i = 1 To Len(s)
        ch = UCase$(Mid$(s, i, 1))
        If ch < "A" Or ch > "Z" Then
            IsAlpha = False
            Exit Function
        End If
    Next i
    IsAlpha = True
End Function
