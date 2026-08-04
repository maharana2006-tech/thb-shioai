' =============================================================================
' BackendConfig.bas  — shared macro helpers (Sprint 48 revision)
' =============================================================================
' Central place for the backend URL + Bearer token so ValidateAll and
' ValidateAddresses stop drifting.
'
' URL source, in precedence order:
'   1. Reference sheet cell Z1 (the "hidden config cell"). Reference is
'      state=veryHidden, so admins temporarily unhide it, set Z1 to the
'      backend URL (e.g. https://ship.example.com), re-hide. VBA reads
'      it without ever un-hiding the sheet in the operator's session.
'   2. Fall back to the hardcoded DEFAULT_BACKEND below.
'   3. On first missing read, prompt the operator via InputBox and cache
'      the answer for the rest of the Excel session.
'
' Token: prompted once via InputBox, cached in a module Static so the two
' macros don't re-prompt within one session.
'
' How to inject: Alt+F11 → File → Import File → pick BackendConfig.bas
' FIRST, then ValidateAll.bas + ValidateAddresses.bas which call into it.
' =============================================================================

Option Explicit

Private Const DEFAULT_BACKEND As String = "http://localhost:8080"
Private Const CONFIG_SHEET As String = "Reference"
Private Const CONFIG_URL_CELL As String = "Z1"

Private cachedUrl As String
Private cachedToken As String

' Get the backend URL. Reads Reference!Z1 first, falls back to hardcoded
' default, prompts once if both empty.
Public Function BackendUrl() As String
    If Len(cachedUrl) > 0 Then
        BackendUrl = cachedUrl
        Exit Function
    End If
    Dim ws As Worksheet
    On Error Resume Next
    Set ws = ThisWorkbook.Worksheets(CONFIG_SHEET)
    On Error GoTo 0
    Dim fromSheet As String
    If Not ws Is Nothing Then
        fromSheet = Trim(CStr(ws.Range(CONFIG_URL_CELL).Value))
    End If
    If Len(fromSheet) > 0 Then
        cachedUrl = fromSheet
    ElseIf Len(DEFAULT_BACKEND) > 0 Then
        cachedUrl = DEFAULT_BACKEND
    Else
        cachedUrl = InputBox("Backend URL (e.g. http://localhost:8080):", "Backend URL")
    End If
    ' Strip trailing slash for consistent concatenation downstream.
    If Right$(cachedUrl, 1) = "/" Then cachedUrl = Left$(cachedUrl, Len(cachedUrl) - 1)
    BackendUrl = cachedUrl
End Function

' Get the operator's Bearer token, prompting once per Excel session.
Public Function BearerToken() As String
    If Len(cachedToken) > 0 Then
        BearerToken = cachedToken
        Exit Function
    End If
    Dim t As String
    t = InputBox("Paste your Bearer token (browser localStorage 'multiship_token'):", _
                 "Backend token")
    cachedToken = t
    BearerToken = t
End Function

' Test-only helper: clear the caches (used from the Immediate window when
' switching backends or re-authenticating mid-session).
Public Sub ResetBackendCache()
    cachedUrl = ""
    cachedToken = ""
End Sub
