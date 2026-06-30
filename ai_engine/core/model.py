import torch
import torch.nn as nn
import torch.nn.functional as F

# ══════════════════════════════════════════════════════════════════════════════
# ECAPA-TDNN Layer Definitions
# ══════════════════════════════════════════════════════════════════════════════
class SEBlock1d(nn.Module):
    def __init__(self, ch, r=4):
        super().__init__()
        self.pool = nn.AdaptiveAvgPool1d(1)
        self.fc   = nn.Sequential(
            nn.Linear(ch, ch // r, bias=False), nn.ReLU(inplace=True),
            nn.Linear(ch // r, ch, bias=False), nn.Sigmoid())
    def forward(self, x):
        b, c, _ = x.shape
        return x * self.fc(self.pool(x).view(b, c)).view(b, c, 1)

class Res2Conv1dReluBn(nn.Module):
    def __init__(self, ch, scale=8, dil=1):
        super().__init__()
        self.width = ch // scale
        self.nums  = scale - 1
        self.convs = nn.ModuleList([
            nn.Conv1d(self.width, self.width, 3, dilation=dil, padding=dil, bias=False)
            for _ in range(self.nums)])
        self.bns = nn.ModuleList([nn.BatchNorm1d(self.width) for _ in range(self.nums)])
    def forward(self, x):
        spx = torch.split(x, self.width, 1)
        out, sp = [], None
        for i, (c, b) in enumerate(zip(self.convs, self.bns)):
            sp = spx[i] if i == 0 else sp + spx[i]
            sp = F.relu(b(c(sp)))
            out.append(sp)
        out.append(spx[self.nums])
        return torch.cat(out, 1)

class SERes2Block(nn.Module):
    def __init__(self, ch, dil, scale=8):
        super().__init__()
        self.conv1 = nn.Conv1d(ch, ch, 1, bias=False)
        self.bn1   = nn.BatchNorm1d(ch)
        self.res2  = Res2Conv1dReluBn(ch, scale, dil)
        self.conv3 = nn.Conv1d(ch, ch, 1, bias=False)
        self.bn3   = nn.BatchNorm1d(ch)
        self.se    = SEBlock1d(ch)
        self.relu  = nn.ReLU(inplace=True)
    def forward(self, x, residual=None):
        if residual is None: residual = x
        o = self.relu(self.bn1(self.conv1(x)))
        o = self.res2(o)
        o = self.relu(self.bn3(self.conv3(o)))
        o = self.se(o)
        return o + residual

class AttentivePool(nn.Module):
    def __init__(self, C):
        super().__init__()
        self.tdnn = nn.Conv1d(C * 3, 128, 1)
        self.attn = nn.Conv1d(128, C, 1)
    def forward(self, x):
        mu    = x.mean(2, keepdim=True).expand_as(x)
        sg    = x.var(2,  keepdim=True).clamp(1e-4).sqrt().expand_as(x)
        ctx   = torch.cat([x, mu, sg], 1)
        alpha = F.softmax(self.attn(torch.tanh(self.tdnn(ctx))), dim=2)
        mean  = (alpha * x).sum(2)
        std   = ((alpha * x.pow(2)).sum(2) - mean.pow(2) + 1e-4).sqrt()
        return torch.cat([mean, std], 1)

class ECAPA_TDNN(nn.Module):
    def __init__(self, C=512, emb=192, scale=8):
        super().__init__()
        self.stem     = nn.Sequential(
            nn.Conv1d(80, C, 5, padding=2, bias=False),
            nn.BatchNorm1d(C), nn.ReLU(inplace=True))
        self.block1   = SERes2Block(C, 2, scale)
        self.block2   = SERes2Block(C, 3, scale)
        self.block3   = SERes2Block(C, 4, scale)
        self.mfa_conv = nn.Conv1d(C * 3, C * 3, 1)
        self.pooling  = AttentivePool(C * 3)
        self.bn_pool  = nn.BatchNorm1d(C * 6)
        self.fc_embed = nn.Linear(C * 6, emb)
        self.bn_embed = nn.BatchNorm1d(emb)
    def forward(self, x):
        h  = self.stem(x)
        o1 = self.block1(h,  residual=h)
        o2 = self.block2(o1, residual=h + o1)
        o3 = self.block3(o2, residual=h + o1 + o2)
        mfa   = self.mfa_conv(torch.cat([o1, o2, o3], 1))
        stats = self.pooling(mfa)
        emb   = self.bn_embed(self.fc_embed(self.bn_pool(stats)))
        return F.normalize(emb, p=2, dim=1)
