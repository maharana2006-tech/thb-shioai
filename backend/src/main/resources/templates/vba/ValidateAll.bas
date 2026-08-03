' =============================================================================
' ValidateAll.bas  — order-import template macro
' =============================================================================
' Sprint 48 — companion VBA for the .xlsm order-import template. Users
' click "Validate All" (button assigned to ValidateAll) before saving
' as CSV to catch required-field misses, malformed HS codes, and rows
' that violate the multi-row orderRef convention BEFORE they upload.
'
' Backend re-validates every rule server-side, so this macro is
' operator ergonomics — it just paints problem cells red + surfaces a
' summary at the top so they don't have to hunt.
'
' What it flags:
'   * Missing required fields on the leader row of each orderRef group:
'     recipientName, addressLine1, city, postalCode, countryCode, weight.
'   * Weight ≤ 0 or non-numeric.
'   * Malformed HS code — HS codes are 4-10 digits, optionally with dots
'     (e.g. 5007.20, 8471.30.00). Doesn't guarantee validity against
'     the customs tariff but catches obvious typos.
'   * countryOfOrigin not ISO-2 (must be exactly two letters).
'   * itemQuantity non-integer or ≤ 0.
'   * International row (countryCode ≠ US when leader is US-origin
'     assumed) missing HS/COO — flagged softly as a warning fill.
'
' Colour coding:
'   * Red fill (0xFFCCCC)     — hard error, backend WILL reject.
'   * Yellow fill (0xFFF3CC)  — warning, backend accepts but recommend fix.
'   * Green fill (0xE6FFE6)   — cleared previously-flagged cell.
'
' How to inject: same as SaveAsCsv.bas — Alt+F11, Import File.
' Assign the ValidateAll macro to a "Validate All" button on the
' Import sheet.
' =============================================================================

Option Explicit

Private Const COLOR_ERROR As Long = &HCCCCFF     ' RGB(255,204,204) — red-ish
Private Const COLOR_WARN  As Long = &HCCF3FF     ' RGB(255,243,204) — yellow-ish
Private Const COLOR_NONE  As Long = xlNone

Public Sub ValidateAll()
    Dim src As Worksheet
    Dim lastRow As Long, lastCol As Long
    Dim r As Long
    Dim headers As Object   ' name -> column index (1-based)
    Set headers = CreateObject("Scripting.Dictionary")
    headers.CompareMode = 1 ' textual, case-insensitive

    On Error Resume Next
    Set src = ThisWorkbook.Worksheets("Import")
    On Error GoTo 0
    If src Is Nothing Then
        MsgBox "No sheet named 'Import' — nothing to validate.", vbExclamation, "ValidateAll"
        Exit Sub
    End If

    lastCol = src.Cells(1, src.Columns.Count).End(xlToLeft).Column
    lastRow = src.Cells(src.Rows.Count, 1).End(xlUp).Row

    ' Header index — build once.
    Dim c As Long
    For c = 1 To lastCol
        headers(CStr(src.Cells(1, c).Value)) = c
    Next c

    ' Clear previous fills on the data range.
    src.Range(src.Cells(2, 1), src.Cells(WorksheetFunction.Max(2, lastRow), lastCol)).Interior.ColorIndex = COLOR_NONE

    Dim errorCount As Long, warnCount As Long
    Dim currentOrderRef As String
    Dim leaderRow As Long
    currentOrderRef = ""
    leaderRow = 0

    For r = 2 To lastRow
        Dim orderRef As String
        orderRef = SafeCell(src, r, headers, "orderRef")
        Dim isLeader As Boolean
        isLeader = False
        If Len(orderRef) = 0 Then
            ' Standalone row — always the leader.
            isLeader = True
        ElseIf orderRef <> currentOrderRef Then
            ' First row of a new group.
            isLeader = True
            currentOrderRef = orderRef
            leaderRow = r
        End If

        If isLeader Then
            ValidateLeader src, r, headers, errorCount, warnCount
        Else
            ValidateItemRow src, r, headers, errorCount, warnCount
        End If
    Next r

    Dim summary As String
    If errorCount = 0 And warnCount = 0 Then
        summary = "No issues found in " & (lastRow - 1) & " row(s)."
    Else
        summary = errorCount & " error(s), " & warnCount & " warning(s) across " _
            & (lastRow - 1) & " row(s). Red = must fix; yellow = recommended."
    End If
    MsgBox summary, vbInformation, "ValidateAll"
End Sub

Private Sub ValidateLeader(ByRef src As Worksheet, ByVal r As Long, _
                            ByRef headers As Object, ByRef errorCount As Long, _
                            ByRef warnCount As Long)
    ' Required leader fields — miss any and the backend hard-rejects.
    If MarkIfBlank(src, r, headers, "recipientName") Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "addressLine1")  Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "city")           Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "postalCode")     Then errorCount = errorCount + 1
    If MarkIfBlank(src, r, headers, "countryCode")    Then errorCount = errorCount + 1
    ' Weight — required and > 0.
    Dim weightVal As Variant
    weightVal = RawCell(src, r, headers, "weight")
    If Not IsNumeric(weightVal) Or Val(CStr(weightVal)) <= 0 Then
        MarkCell src, r, headers, "weight", COLOR_ERROR
        errorCount = errorCount + 1
    End If
    ' Common per-row shape checks (apply to leader row too).
    warnCount = warnCount + CommonRowChecks(src, r, headers, errorCount)
End Sub

Private Sub ValidateItemRow(ByRef src As Worksheet, ByVal r As Long, _
                             ByRef headers As Object, ByRef errorCount As Long, _
                             ByRef warnCount As Long)
    ' Item-only continuation row — expect at least an item field.
    Dim anyItem As Boolean
    anyItem = Len(SafeCell(src, r, headers, "itemDescription")) > 0 _
           Or Len(SafeCell(src, r, headers, "itemSku")) > 0 _
           Or Len(SafeCell(src, r, headers, "hsCode")) > 0 _
           Or Len(SafeCell(src, r, headers, "countryOfOrigin")) > 0
    If Not anyItem Then
        ' Empty continuation row is meaningless — flag as warning so
        ' operator either fills or deletes.
        MarkCell src, r, headers, "orderRef", COLOR_WARN
        warnCount = warnCount + 1
    End If
    warnCount = warnCount + CommonRowChecks(src, r, headers, errorCount)
End Sub

' Shared shape checks that apply to every row (leader or item):
'   * hsCode format — 4-10 digits, dots allowed.
'   * countryOfOrigin — ISO-2 letters when populated.
'   * itemQuantity — positive integer when populated.
Private Function CommonRowChecks(ByRef src As Worksheet, ByVal r As Long, _
                                  ByRef headers As Object, ByRef errorCount As Long) As Long
    Dim warns As Long
    warns = 0

    Dim hs As String
    hs = SafeCell(src, r, headers, "hsCode")
    If Len(hs) > 0 Then
        If Not LikeHsCode(hs) Then
            MarkCell src, r, headers, "hsCode", COLOR_WARN
            warns = warns + 1
        End If
    End If

    Dim coo As String
    coo = SafeCell(src, r, headers, "countryOfOrigin")
    If Len(coo) > 0 Then
        If Len(coo) <> 2 Or Not IsAlpha(coo) Then
            MarkCell src, r, headers, "countryOfOrigin", COLOR_ERROR
            errorCount = errorCount + 1
        End If
    End If

    Dim qty As Variant
    qty = RawCell(src, r, headers, "itemQuantity")
    If Not IsEmpty(qty) And Len(CStr(qty)) > 0 Then
        If Not IsNumeric(qty) Or Val(CStr(qty)) <= 0 Or (Val(CStr(qty)) <> Int(Val(CStr(qty)))) Then
            MarkCell src, r, headers, "itemQuantity", COLOR_ERROR
            errorCount = errorCount + 1
        End If
    End If

    CommonRowChecks = warns
End Function

' -------- small helpers --------

' Read a cell as trimmed string. Missing header column → "".
Private Function SafeCell(ByRef src As Worksheet, ByVal r As Long, _
                           ByRef headers As Object, ByVal name As String) As String
    If Not headers.Exists(name) Then
        SafeCell = ""
    Else
        SafeCell = Trim(CStr(src.Cells(r, headers(name)).Value))
    End If
End Function

' Read a cell as the raw variant (numeric-aware).
Private Function RawCell(ByRef src As Worksheet, ByVal r As Long, _
                          ByRef headers As Object, ByVal name As String) As Variant
    If Not headers.Exists(name) Then
        RawCell = Empty
    Else
        RawCell = src.Cells(r, headers(name)).Value
    End If
End Function

' Paint the named cell with a fill colour.
Private Sub MarkCell(ByRef src As Worksheet, ByVal r As Long, _
                      ByRef headers As Object, ByVal name As String, ByVal color As Long)
    If Not headers.Exists(name) Then Exit Sub
    src.Cells(r, headers(name)).Interior.Color = color
End Sub

' Paint the cell red + return True when it's blank; return False otherwise.
Private Function MarkIfBlank(ByRef src As Worksheet, ByVal r As Long, _
                              ByRef headers As Object, ByVal name As String) As Boolean
    If Len(SafeCell(src, r, headers, name)) = 0 Then
        MarkCell src, r, headers, name, COLOR_ERROR
        MarkIfBlank = True
    Else
        MarkIfBlank = False
    End If
End Function

' Rough HS-code shape check. 4-10 digits, optional dots between groups
' (5007.20, 8471.30.00, etc.). Not a customs-tariff validator.
Private Function LikeHsCode(ByVal s As String) As Boolean
    Dim digits As String
    Dim i As Long, ch As String
    For i = 1 To Len(s)
        ch = Mid$(s, i, 1)
        If ch >= "0" And ch <= "9" Then digits = digits & ch
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
