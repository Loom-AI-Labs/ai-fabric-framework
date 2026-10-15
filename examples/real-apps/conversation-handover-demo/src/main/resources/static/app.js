const state = {
  conversationId: null,
  visitor: null,
  operatorAuthenticated: false,
  selectedConversationId: null,
  operatorConversation: null,
  intakeDismissed: false,
  lastIntakeRequestMessageId: null,
};

const $ = (id) => document.getElementById(id);
const key = () => crypto.randomUUID();

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    credentials: "same-origin",
    cache: "no-store",
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || `Request failed (${response.status})`);
  return body;
}

async function start() {
  const [session, health] = await Promise.all([
    api("/api/demo/session", { method: "POST", body: "{}" }),
    api("/api/demo/health"),
  ]);
  state.conversationId = session.conversationId;
  state.visitor = session.conversation;
  if (health.publicOperatorEnabled && health.demoOperatorAccessCode) {
    $("operator-code").type = "text";
    $("operator-code").value = health.demoOperatorAccessCode;
    $("operator-access-hint").textContent = "Public demo code. This operator can access only your own session conversation. Use sample contact details.";
  }
  renderVisitor();
  window.setInterval(refresh, 2000);
}

async function refresh() {
  if (state.conversationId) {
    state.visitor = await api(`/api/demo/conversations/${encodeURIComponent(state.conversationId)}`);
    renderVisitor();
  }
  if (state.operatorAuthenticated) await refreshQueue();
}

function renderMessages(root, messages) {
  root.textContent = "";
  (messages || []).forEach((message) => {
    const article = document.createElement("article");
    article.className = "message";
    article.dataset.role = message.role;
    const meta = document.createElement("div");
    meta.className = "message-meta";
    const author = document.createElement("span");
    author.textContent = message.role === "USER" ? "Visitor" : message.role === "HUMAN" ? "Human operator" : message.role === "AI" ? "Demo AI" : "System";
    const sequence = document.createElement("span");
    sequence.textContent = `#${message.sequence}`;
    meta.append(author, sequence);
    const content = document.createElement("p");
    content.textContent = message.content;
    article.append(meta, content);
    if (message.metadata?.action) {
      const proof = document.createElement("div");
      proof.className = "action-proof";
      proof.textContent = `AI action · ${message.metadata.action}`;
      article.append(proof);
    }
    root.append(article);
  });
  root.scrollTop = root.scrollHeight;
}

function renderVisitor() {
  if (!state.visitor) return;
  const control = state.visitor.control;
  const latestIntakeRequest = [...(state.visitor.messages || [])]
    .reverse()
    .find((message) => message.metadata?.outcome === "HANDOVER_INTAKE_REQUIRED");
  if (latestIntakeRequest?.messageId && latestIntakeRequest.messageId !== state.lastIntakeRequestMessageId) {
    state.lastIntakeRequestMessageId = latestIntakeRequest.messageId;
    state.intakeDismissed = false;
  }
  $("visitor-state").textContent = control.state.replaceAll("_", " ");
  $("visitor-state").dataset.state = control.state;
  $("visitor-authority").textContent = authorityCopy(control.state, control.version, control.controlEpoch);
  renderMessages($("visitor-messages"), state.visitor.messages);
  $("request-human").hidden = control.state !== "AI_ACTIVE";
  $("cancel-human").hidden = control.state !== "HUMAN_PENDING";
  const closed = control.state === "CLOSED" || control.state === "AI_RESUME_PENDING";
  $("visitor-message").disabled = closed;
  $("visitor-form").querySelector("button").disabled = closed;
  $("visitor-message").placeholder = control.state === "AI_ACTIVE"
    ? "Ask AI a question or request a human operator"
    : "Continue the conversation with the human operator";
  if (control.state !== "AI_ACTIVE") {
    closeIntake(false);
  } else if (state.visitor.handoverIntakeRequired && !state.intakeDismissed) {
    openIntake();
  }
}

function authorityCopy(controlState, version, epoch) {
  const owner = controlState === "AI_ACTIVE" ? "AI may answer" : controlState === "HUMAN_ACTIVE" ? "Only the human operator may answer" : controlState === "HUMAN_PENDING" ? "AI is paused while a human is requested" : controlState === "CLOSED" ? "Conversation closed" : "Returning authority to AI";
  return `${owner}. Framework control version ${version}; authority epoch ${epoch}.`;
}

$("visitor-form").onsubmit = async (event) => {
  event.preventDefault();
  const text = $("visitor-message").value.trim();
  if (!text) return;
  try {
    state.visitor = await api(`/api/demo/conversations/${state.conversationId}/messages`, {
      method: "POST",
      body: JSON.stringify({ text, clientMessageId: key() }),
    });
    $("visitor-message").value = "";
    renderVisitor();
  } catch (error) { toast(error.message); }
};

$("request-human").onclick = async () => {
  state.intakeDismissed = false;
  openIntake();
};

function openIntake() {
  $("handover-intake-overlay").hidden = false;
  window.setTimeout(() => $("handover-email").focus(), 0);
}

function closeIntake(dismiss = true) {
  $("handover-intake-overlay").hidden = true;
  if (dismiss) state.intakeDismissed = true;
}

$("close-handover-intake").onclick = () => closeIntake();
$("cancel-handover-intake").onclick = () => closeIntake();

$("handover-phone").oninput = () => {
  const hasPhone = Boolean($("handover-phone").value.trim());
  $("handover-phone-consent-row").hidden = !hasPhone;
  $("handover-phone-consent").required = hasPhone;
  if (!hasPhone) $("handover-phone-consent").checked = false;
};

$("handover-intake-form").onsubmit = async (event) => {
  event.preventDefault();
  const email = $("handover-email").value.trim();
  const name = $("handover-name").value.trim();
  const phone = $("handover-phone").value.trim();
  if (!email || !$("handover-email-consent").checked) {
    toast("Email and consent are required before handover.");
    return;
  }
  if (phone && !$("handover-phone-consent").checked) {
    toast("Consent is required to share the optional phone number.");
    return;
  }
  const intake = { email };
  if (name) intake.name = name;
  if (phone) intake.phone = phone;
  const consentedFields = ["email"];
  if (phone) consentedFields.push("phone");
  try {
    state.visitor = await api(`/api/demo/conversations/${state.conversationId}/handover`, {
      method: "POST",
      body: JSON.stringify({
        expectedVersion: state.visitor.control.version,
        idempotencyKey: key(),
        intake,
        consentedFields,
      }),
    });
    state.intakeDismissed = false;
    $("handover-intake-form").reset();
    $("handover-phone-consent-row").hidden = true;
    closeIntake(false);
    renderVisitor();
  } catch (error) { toast(error.message); }
};

$("cancel-human").onclick = async () => {
  try {
    state.visitor = await api(`/api/demo/conversations/${state.conversationId}/handover/cancel`, {
      method: "POST",
      body: JSON.stringify({ expectedVersion: state.visitor.control.version, idempotencyKey: key() }),
    });
    renderVisitor();
  } catch (error) { toast(error.message); }
};

$("operator-login").onsubmit = async (event) => {
  event.preventDefault();
  try {
    await api("/api/demo/operator/session", {
      method: "POST",
      body: JSON.stringify({ accessCode: $("operator-code").value }),
    });
    state.operatorAuthenticated = true;
    $("operator-login").hidden = true;
    $("operator-workspace").hidden = false;
    $("operator-state").textContent = "Operator active";
    await refreshQueue();
  } catch (error) { toast(error.message); }
};

async function refreshQueue() {
  const queue = await api("/api/demo/operator/conversations");
  const root = $("operator-queue");
  root.textContent = "";
  queue.forEach((item) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `queue-item${item.conversationId === state.selectedConversationId ? " selected" : ""}`;
    const title = document.createElement("strong");
    title.textContent = item.safeLabel;
    const meta = document.createElement("small");
    meta.textContent = `${item.state.replaceAll("_", " ")} · version ${item.version}`;
    button.append(title, meta);
    button.onclick = () => selectConversation(item.conversationId);
    root.append(button);
  });
  if (state.selectedConversationId) await selectConversation(state.selectedConversationId, false);
}

async function selectConversation(conversationId, rerenderQueue = true) {
  state.selectedConversationId = conversationId;
  state.operatorConversation = await api(`/api/demo/operator/conversations/${encodeURIComponent(conversationId)}`);
  $("operator-empty").hidden = true;
  $("operator-conversation").hidden = false;
  renderOperatorConversation();
  if (rerenderQueue) await refreshQueue();
}

function renderOperatorConversation() {
  const view = state.operatorConversation;
  if (!view) return;
  renderMessages($("operator-messages"), view.messages);
  const status = view.control.state;
  const humanActive = status === "HUMAN_ACTIVE";
  $("operator-state").textContent = status.replaceAll("_", " ");
  $("operator-state").dataset.state = status;
  $("claim").hidden = status !== "HUMAN_PENDING";
  $("return-ai").hidden = !humanActive;
  $("close").hidden = !humanActive;
  $("operator-message").disabled = !humanActive;
  $("operator-form").querySelector("button").disabled = !humanActive;
  const contact = view.contactIntake;
  $("operator-contact").hidden = !contact;
  if (contact) {
    $("operator-contact-email").textContent = contact.email;
    $("operator-contact-name-row").hidden = !contact.name;
    $("operator-contact-name").textContent = contact.name || "";
    $("operator-contact-phone-row").hidden = !contact.phone;
    $("operator-contact-phone").textContent = contact.phone || "";
  }
}

async function transition(operation) {
  try {
    state.operatorConversation = await api(`/api/demo/operator/conversations/${state.selectedConversationId}/${operation}`, {
      method: "POST",
      body: JSON.stringify({ expectedVersion: state.operatorConversation.control.version, idempotencyKey: key() }),
    });
    renderOperatorConversation();
    await refreshQueue();
  } catch (error) { toast(error.message); }
}

$("claim").onclick = () => transition("claim");
$("return-ai").onclick = () => transition("return-to-ai");
$("close").onclick = () => transition("close");
$("refresh-queue").onclick = () => refreshQueue().catch((error) => toast(error.message));

$("operator-form").onsubmit = async (event) => {
  event.preventDefault();
  const text = $("operator-message").value.trim();
  if (!text) return;
  try {
    state.operatorConversation = await api(`/api/demo/operator/conversations/${state.selectedConversationId}/messages`, {
      method: "POST",
      body: JSON.stringify({ text, clientMessageId: key() }),
    });
    $("operator-message").value = "";
    renderOperatorConversation();
  } catch (error) { toast(error.message); }
};

function toast(message) {
  $("toast").textContent = message;
  $("toast").hidden = false;
  window.setTimeout(() => { $("toast").hidden = true; }, 4000);
}

start().catch((error) => toast(error.message));
