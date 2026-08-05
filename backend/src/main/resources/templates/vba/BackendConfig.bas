' =============================================================================
' BackendConfig.bas  — shared macro helpers (Sprint 48 revision, patched)
' =============================================================================
' Central place for the backend URL + Bearer token so ValidateAll and
' ValidateAddresses stop drifting.
'
' URL source, in precedence order:
'   1. Workbook custom document property "BackendUrl". Admins set this
'      once per environment via SetBackendUrl (or File → Info → Properties
'      → Advanced Properties → Custom tab).
'   2. Fall back to the hardcoded DEFAULT_BACKEND constant below.
'   3. On first missing read, prompt the operator via InputBox and cache
'      the answer for the rest of the Excel session.
'
' Token source, in precedence order:
'   1. Workbook custom document property "BackendToken" (only when the
'      operator previously chose "remember" on the prompt).
'   2. In-session cache (Static in this module).
'   3. Prompted via InputBox on first call per session. The prompt asks
'      whether to remember the token in the workbook (opt-in — anyone
'      with the file could read it, so default is No).
'
' History note: prior revisions read Reference!Z1 as the URL source. That
' cell collides with the header row of the _ServiceOrigins named range,
' so the macro was reading the literal string "_ServiceOrigins" and
' MSXML failed at the transport layer with system error 2147012890.
' CustomDocumentProperties has no such collision.
'
' How to inject: Alt+F11 → File → Import File → pick BackendConfig.bas
' FIRST, then ValidateAll.bas + ValidateAddresses.bas which call into it.
' =============================================================================

Option Explicit

Private Const DEFAULT_BACKEND As String = "http://localhost:8080"
Private Const PROP_URL   As String = "BackendUrl"
Private Const PROP_TOKEN As String = "BackendToken"

Private cachedUrl As String
Private cachedToken As String

' Get the backend URL. Reads the workbook custom document property first,
' falls back to the hardcoded default, prompts once if both are empty.
Public Function BackendUrl() As String
    If Len(cachedUrl) > 0 Then
        BackendUrl = cachedUrl
        Exit Function
    End If
    Dim fromProp As String
    fromProp = Trim(ReadDocProperty(PROP_URL))
    If Len(fromProp) > 0 Then
        cachedUrl = fromProp
    ElseIf Len(DEFAULT_BACKEND) > 0 Then
        cachedUrl = DEFAULT_BACKEND
    Else
        cachedUrl = InputBox("Backend URL (e.g. http://localhost:8080):", "Backend URL")
    End If
    ' Strip trailing slash for consistent concatenation downstream.
    If Right$(cachedUrl, 1) = "/" Then cachedUrl = Left$(cachedUrl, Len(cachedUrl) - 1)
    BackendUrl = cachedUrl
End Function

' Get the operator's Bearer token. Reads the workbook custom document
' property first (only present if operator previously chose "remember"),
' falls back to session cache, prompts once per session otherwise.
Public Function BearerToken() As String
    If Len(cachedToken) > 0 Then
        BearerToken = cachedToken
        Exit Function
    End If
    Dim fromProp As String
    fromProp = Trim(ReadDocProperty(PROP_TOKEN))
    If Len(fromProp) > 0 Then
        cachedToken = fromProp
        BearerToken = fromProp
        Exit Function
    End If
    Dim t As String
    t = InputBox( _
        "Paste your Bearer token (from the browser's localStorage 'multiship_token'):", _
        "Backend token")
    If Len(t) = 0 Then
        BearerToken = ""
        Exit Function
    End If
    cachedToken = t
    ' Ask whether to remember across sessions. Default is No — tokens are
    ' sensitive and anyone with the file can read the stored value.
    Dim remember As VbMsgBoxResult
    remember = MsgBox( _
        "Remember this token inside the workbook for future sessions?" & vbCrLf & vbCrLf & _
        "WARNING: anyone with a copy of this file can read the stored token." & vbCrLf & _
        "Recommended: No.", _
        vbYesNo + vbDefaultButton2 + vbExclamation, "Remember token?")
    If remember = vbYes Then
        WriteDocProperty PROP_TOKEN, t
    End If
    BearerToken = t
End Function

' Admin helper — prompt for the backend URL and store it in the workbook
' custom document properties. Call once per environment; the value
' persists across Excel sessions.
Public Sub SetBackendUrl()
    Dim current As String
    current = ReadDocProperty(PROP_URL)
    Dim entered As String
    entered = InputBox( _
        "Backend URL (e.g. http://localhost:8080 or https://ship.example.com):", _
        "Set backend URL", current)
    If Len(entered) = 0 Then Exit Sub
    ' Strip trailing slash for consistency with the runtime accessor.
    If Right$(entered, 1) = "/" Then entered = Left$(entered, Len(entered) - 1)
    WriteDocProperty PROP_URL, entered
    cachedUrl = entered
    MsgBox "Backend URL saved to workbook: " & entered & vbCrLf & _
           "Remember to save the workbook (.xlsm) to persist it on disk.", _
           vbInformation, "Set backend URL"
End Sub

' Admin helper — clear the stored bearer token from the workbook and
' the in-session cache. Use when the token has expired or before
' sharing the workbook.
Public Sub ClearBackendToken()
    cachedToken = ""
    DeleteDocProperty PROP_TOKEN
    MsgBox "Stored bearer token cleared. You will be prompted again on the next Validate click.", _
           vbInformation, "Clear backend token"
End Sub

' Test-only helper: clear the in-session caches (used from the Immediate
' window when switching backends mid-session). Does NOT touch stored
' document properties — use ClearBackendToken for that.
Public Sub ResetBackendCache()
    cachedUrl = ""
    cachedToken = ""
End Sub

' ---- CustomDocumentProperties helpers ----
' The collection raises an error if the property doesn't exist, so we
' swallow that with On Error Resume Next. Writes overwrite when the
' property exists, otherwise Add.

Private Function ReadDocProperty(ByVal name As String) As String
    Dim v As String
    On Error Resume Next
    v = CStr(ThisWorkbook.CustomDocumentProperties(name).Value)
    On Error GoTo 0
    ReadDocProperty = v
End Function

Private Sub WriteDocProperty(ByVal name As String, ByVal value As String)
    On Error Resume Next
    ThisWorkbook.CustomDocumentProperties(name).Value = value
    If Err.Number <> 0 Then
        Err.Clear
        ThisWorkbook.CustomDocumentProperties.Add _
            name:=name, LinkToContent:=False, _
            Type:=msoPropertyTypeString, value:=value
    End If
    On Error GoTo 0
End Sub

Private Sub DeleteDocProperty(ByVal name As String)
    On Error Resume Next
    ThisWorkbook.CustomDocumentProperties(name).Delete
    On Error GoTo 0
End Sub
