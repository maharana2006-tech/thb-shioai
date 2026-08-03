' =============================================================================
' SaveAsCsv.bas  — order-import template macro
' =============================================================================
' Sprint 48 — companion VBA for the .xlsm variant of the order-import
' template. Users click "Save as CSV" (button assigned to SaveAsCsv) and
' the workbook writes a UTF-8 CSV next to itself, with the same rows the
' operator filled on the Import sheet.
'
' Why not rely on Excel's built-in "Save As → CSV UTF-8"?
'   * Excel's dialog defaults to the last-used folder, not the workbook's
'     folder — operators upload the wrong file periodically.
'   * Excel's CSV writer occasionally injects `="value"` armour when a
'     cell looks numeric-but-isn't (leading zeros on postcodes, HS
'     codes like "5007.20") and downstream parsers see the `=` prefix.
'     This routine writes plain CSV, no armour.
'   * UTF-8 BOM is written explicitly so the backend's Save-As-CSV
'     tolerance path never has to guess.
'
' Data source: the "Import" sheet, columns A through (last populated
' header). Rows 2..LastUsedRow are written; blank rows are skipped.
' Rows past the sample block that the operator hasn't touched are
' implicitly skipped by the blank-row filter.
'
' How to inject into your .xlsm:
'   1. Open the .xlsm in Excel.
'   2. Alt+F11 → File → Import File → select this SaveAsCsv.bas.
'   3. Insert → Module (if none exists) → paste this whole block if
'      Import File is unavailable.
'   4. On the Import sheet: Developer → Insert → Button (Form Control) →
'      draw button → assign macro "SaveAsCsv" → label it "Save as CSV".
'   5. Save the workbook as .xlsm (macro-enabled) and drop it in
'      backend/src/main/resources/templates/order-import-template.xlsm.
'
' To have the download endpoint serve the .xlsm instead of the .xlsx
' when it exists, small backend follow-up (2 lines in
' OrderImportTemplateBuilder / OrderImportServiceImpl): check for the
' resource file first, fall through to the generated .xlsx otherwise.
' =============================================================================

Option Explicit

Public Sub SaveAsCsv()
    Dim src As Worksheet
    Dim outPath As String
    Dim ff As Integer
    Dim lastRow As Long, lastCol As Long
    Dim r As Long, c As Long
    Dim cellValue As String
    Dim lineBuf As String
    Dim wroteAny As Boolean
    Dim outBytes() As Byte

    ' 1. Locate the Import sheet — same name our backend generator writes.
    On Error Resume Next
    Set src = ThisWorkbook.Worksheets("Import")
    On Error GoTo 0
    If src Is Nothing Then
        MsgBox "No sheet named 'Import' — save aborted.", vbExclamation, "SaveAsCsv"
        Exit Sub
    End If

    ' 2. Discover the used range (avoid saving thousands of blank rows).
    lastCol = src.Cells(1, src.Columns.Count).End(xlToLeft).Column
    lastRow = src.Cells(src.Rows.Count, 1).End(xlUp).Row
    If lastRow < 2 Then
        MsgBox "Import sheet has only the header row — save aborted.", vbExclamation, "SaveAsCsv"
        Exit Sub
    End If

    ' 3. Assemble every non-blank row into a single UTF-8 string. Doing it
    '    in memory first is much faster than repeated Print#-to-file, and
    '    it lets us prepend the BOM cleanly.
    Dim buf As String
    For r = 1 To lastRow
        lineBuf = ""
        Dim rowHasData As Boolean
        rowHasData = False
        For c = 1 To lastCol
            cellValue = CStr(src.Cells(r, c).Value)
            If Len(cellValue) > 0 Then rowHasData = True
            lineBuf = lineBuf & EscapeCsv(cellValue)
            If c < lastCol Then lineBuf = lineBuf & ","
        Next c
        ' Header (row 1) is always kept; skip data rows that are entirely blank.
        If r = 1 Or rowHasData Then
            buf = buf & lineBuf & vbCrLf
            wroteAny = True
        End If
    Next r

    If Not wroteAny Then
        MsgBox "Nothing to save (no non-blank rows).", vbExclamation, "SaveAsCsv"
        Exit Sub
    End If

    ' 4. Write UTF-8 bytes (BOM + buffer) next to the workbook.
    outPath = SwapExtension(ThisWorkbook.FullName, ".csv")
    outBytes = StringToUtf8Bytes(buf, True)
    ff = FreeFile
    Open outPath For Binary Access Write Lock Read Write As #ff
    Put #ff, 1, outBytes
    Close #ff

    MsgBox "Saved " & outPath, vbInformation, "SaveAsCsv"
End Sub

' Escape a single cell value per RFC 4180: wrap in " when the value
' contains a comma, quote, or newline; double any embedded quote.
Private Function EscapeCsv(ByVal value As String) As String
    Dim needsQuote As Boolean
    needsQuote = (InStr(value, ",") > 0) Or (InStr(value, """") > 0) _
        Or (InStr(value, vbCr) > 0) Or (InStr(value, vbLf) > 0)
    If needsQuote Then
        EscapeCsv = """" & Replace(value, """", """""") & """"
    Else
        EscapeCsv = value
    End If
End Function

' Convert VB's UCS-2 string to UTF-8 bytes. Prepending the 3-byte BOM
' is optional — we include it so Excel/Notepad opens the file with the
' correct encoding on Windows.
Private Function StringToUtf8Bytes(ByVal s As String, ByVal withBom As Boolean) As Byte()
    Dim ado As Object
    Set ado = CreateObject("ADODB.Stream")
    ado.Type = 2 ' text
    ado.Charset = "utf-8"
    ado.Open
    ado.WriteText s
    ado.Position = 0
    ado.Type = 1 ' binary
    Dim raw() As Byte
    raw = ado.Read
    ado.Close
    If withBom Then
        StringToUtf8Bytes = raw
    Else
        ' ADODB.Stream always prefixes the BOM on utf-8; strip it when unwanted.
        Dim n As Long
        n = UBound(raw) - LBound(raw) + 1 - 3
        ReDim out(0 To n - 1) As Byte
        Dim i As Long
        For i = 0 To n - 1
            out(i) = raw(i + 3)
        Next i
        StringToUtf8Bytes = out
    End If
End Function

' Swap a full-path filename's extension for the given one (dot-prefixed).
Private Function SwapExtension(ByVal path As String, ByVal newExt As String) As String
    Dim dotAt As Long
    dotAt = InStrRev(path, ".")
    If dotAt > 0 Then
        SwapExtension = Left$(path, dotAt - 1) & newExt
    Else
        SwapExtension = path & newExt
    End If
End Function
