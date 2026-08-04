' =============================================================================
' ValidateAddresses.bas  — order-import template macro (Sprint 48)
' =============================================================================
' Address-validate every filled row against its picked carrier's own
' address-validation API. Triggered from the "Validate Addresses" button
' on the Import sheet.
'
' Flow:
'   1. Serialise every non-blank row to JSON (same shape ValidateAll uses).
'   2. POST /api/v1/orders/import/validate-addresses.
'   3. Response echoes rows with any invalid addresses appended to the
'      row's warnings[] list.
'   4. Match each response row back to the source-sheet row and paint
'      recipient / address cells yellow when the carrier flagged them.
'
' Colour coding:
'   Yellow (RGB 255,243,204) — carrier flagged address as invalid or suggested a fix.
'   No fill                  — address was valid OR row had no carrier picked.
'
' Skips rows silently when:
'   * carrierCode is blank (no carrier to call).
'   * required address fields are missing (would fail address-validate anyway;
'     ValidateAll catches those separately).
'
' How to inject: Alt+F11 → File → Import File → pick this .bas. Assign
' macro to a Form Button labelled "Validate Addresses" on the Import sheet.
' =============================================================================

Option Explicit

Private Const BACKEND_BASE_URL As String = "http://localhost:8080"
Private Const COLOR_WARN As Long = &HCCF3FF  ' RGB 255,243,204

Public Sub ValidateAddresses()
    Dim src As Worksheet
    On Error Resume Next
    Set src = ThisWorkbook.Worksheets("Import")
    On Error GoTo 0
    If src Is Nothing Then
        MsgBox "No sheet named 'Import' — nothing to validate.", vbExclamation, "ValidateAddresses"
        Exit Sub
    End If

    Dim lastRow As Long, lastCol As Long
    lastCol = src.Cells(1, src.Columns.Count).End(xlToLeft).Column
    lastRow = src.Cells(src.Rows.Count, 1).End(xlUp).Row
    If lastRow < 2 Then
        MsgBox "Nothing to validate — no data rows.", vbInformation, "ValidateAddresses"
        Exit Sub
    End If

    Dim headers As Object
    Set headers = CreateObject("Scripting.Dictionary")
    headers.CompareMode = 1
    Dim c As Long
    For c = 1 To lastCol
        headers(CStr(src.Cells(1, c).Value)) = c
    Next c

    ' Prompt for token — reuses no session cache since this is a separate
    ' module. If ValidateAll ran first that session, the token typed there
    ' is NOT accessible here (VBA doesn't share module-scope statics).
    Dim token As String
    token = InputBox("Paste your Bearer token (from browser localStorage 'multiship_token'):", _
                     "Backend token")
    If Len(token) = 0 Then
        MsgBox "Cancelled — no token supplied.", vbExclamation, "ValidateAddresses"
        Exit Sub
    End If

    Dim jsonBody As String
    jsonBody = RowsToJson(src, headers, lastRow, lastCol)

    Dim http As Object
    Set http = CreateObject("MSXML2.ServerXMLHTTP.6.0")
    http.Open "POST", BACKEND_BASE_URL & "/api/v1/orders/import/validate-addresses", False
    http.setRequestHeader "Content-Type", "application/json"
    http.setRequestHeader "Authorization", "Bearer " & token
    On Error Resume Next
    http.send jsonBody
    Dim errNum As Long: errNum = Err.Number
    On Error GoTo 0
    If errNum <> 0 Then
        MsgBox "Network / auth failure (" & errNum & "). Backend unreachable?", vbCritical, "ValidateAddresses"
        Exit Sub
    End If
    If http.Status < 200 Or http.Status >= 300 Then
        MsgBox "Backend returned HTTP " & http.Status & vbCrLf & Left(http.responseText, 400), _
               vbCritical, "ValidateAddresses"
        Exit Sub
    End If

    ' Response format: { data: { rows: [ { rowNumber, warnings: [ ... ] } ] } }
    ' Extract per-row warnings and paint the recipient/address cells yellow
    ' when any warning mentions "Address invalid".
    Dim flagged As Long
    Dim response As String
    response = http.responseText
    Dim matches As Object, m As Object
    Set matches = MatchAll(response, """rowNumber""\s*:\s*(\d+)\s*,\s*""[^""]*""[^]]*?""warnings""\s*:\s*\[([^\]]*)\]")
    For Each m In matches
        Dim rowNum As Long
        rowNum = CLng(m.SubMatches(0))
        Dim warnArr As String
        warnArr = m.SubMatches(1)
        If InStr(warnArr, "Address invalid") > 0 Then
            Dim sheetRow As Long
            sheetRow = rowNum + 1
            PaintAddressRow src, sheetRow, headers, COLOR_WARN
            flagged = flagged + 1
        End If
    Next m

    MsgBox flagged & " row(s) flagged by carrier address validation. Yellow cells indicate the address the carrier didn't accept.", _
           vbInformation, "ValidateAddresses"
End Sub

Private Sub PaintAddressRow(ByRef src As Worksheet, ByVal r As Long, _
                             ByRef headers As Object, ByVal color As Long)
    Dim names As Variant
    names = Array("recipientName", "addressLine1", "addressLine2", "city", "state", "postalCode", "countryCode")
    Dim i As Long
    For i = LBound(names) To UBound(names)
        If headers.Exists(names(i)) Then
            src.Cells(r, headers(names(i))).Interior.Color = color
        End If
    Next i
End Sub

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
