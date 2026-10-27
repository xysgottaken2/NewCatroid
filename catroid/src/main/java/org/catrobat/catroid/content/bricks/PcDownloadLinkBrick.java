/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2026 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://www.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.content.bricks;

import org.catrobat.catroid.R;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.actions.ScriptSequenceAction;

/**
 * Downloads a PC component from an arbitrary web address (mirror, direct asset
 * link, an Ubuntu base rootfs, ...) and imports it as a pack. The type field may
 * be left empty, the archive content is then detected automatically.
 */
public class PcDownloadLinkBrick extends FormulaBrick {
    private static final long serialVersionUID = 1L;

    public PcDownloadLinkBrick() {
        addAllowedBrickField(BrickField.PC_PACK_URL, R.id.brick_pc_download_link_url);
        addAllowedBrickField(BrickField.PC_PACK_TYPE, R.id.brick_pc_download_link_type);
    }

    public PcDownloadLinkBrick(String url, String packType) {
        this(new Formula(url), new Formula(packType));
    }

    public PcDownloadLinkBrick(Formula url, Formula packType) {
        this();
        setFormulaWithBrickField(BrickField.PC_PACK_URL, url);
        setFormulaWithBrickField(BrickField.PC_PACK_TYPE, packType);
    }

    @Override
    public int getViewResource() {
        return R.layout.pc_download_link;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory().createPcDownloadLinkAction(
                sprite,
                sequence,
                getFormulaWithBrickField(BrickField.PC_PACK_URL),
                getFormulaWithBrickField(BrickField.PC_PACK_TYPE)));
    }
}
