// ******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2025.
//
// This file is part of Force Field X.
//
// Force Field X is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 3 as published by
// the Free Software Foundation.
//
// Force Field X is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
// Place, Suite 330, Boston, MA 02111-1307 USA
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the
// GNU General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a
// module which is not derived from or based on this library. If you modify this
// library, you may extend this exception to your version of the library, but
// you are not obligated to do so. If you do not wish to do so, delete this
// exception statement from your version.
//
// ******************************************************************************
package ffx.potential.parameters;

import ffx.utilities.FFXProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ffx.potential.parameters.ForceField.ForceFieldType.STRBND;
import static ffx.utilities.Constants.ANG_TO_NM;
import static ffx.utilities.Constants.DEGREES_PER_RADIAN;
import static ffx.utilities.Constants.KCAL_TO_KJ;
import static ffx.utilities.PropertyGroup.EnergyUnitConversion;
import static ffx.utilities.PropertyGroup.PotentialFunctionParameter;
import static java.lang.Double.parseDouble;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Arrays.copyOf;
import static org.apache.commons.math3.util.FastMath.PI;

/**
 * The StretchBendType class defines one out-of-plane angle bending energy type.
 *
 * @author Michael J. Schnieders
 * @since 1.0
 */
@FFXProperty(name = "strbnd", clazz = String.class, propertyGroup = PotentialFunctionParameter, description = """
    [3 integers and 2 reals]
    Provides the values for a single stretch-bend cross term potential parameter.
    The integer modifiers give the atom class numbers for the three kinds of atoms involved in the angle which is to be defined.
    The real number modifiers give the force constant values for the first bond (first two atom classes) with the angle, and the second bond with the angle, respectively.
    The default units for the stretch-bend force constant are kcal/mole/Ang-radian, but this can be controlled via the strbndunit keyword.
    """)
public final class StretchBendType extends BaseType implements Comparator<String> {

  /**
   * Constant <code>units=PI / 180.0</code>
   */
  public static final double DEFAULT_STRBND_UNIT = PI / 180.0;

  @FFXProperty(name = "strbndunit", propertyGroup = EnergyUnitConversion, defaultValue = "(Pi/180)", description = """
      Sets the scale factor needed to convert the energy value computed by the bond stretching-angle bending cross
      term potential into units of kcal/mole. The correct value is force field dependent and typically provided
      in the header of the master force field parameter file.
      """)
  public double strbndunit = DEFAULT_STRBND_UNIT;

  private static final Logger logger = Logger.getLogger(StretchBendType.class.getName());
  /**
   * Atom class for this stretch-bend type.
   */
  public final int[] atomClasses;
  /**
   * Force constants (Kcal/mole/Angstrom-Degrees).
   */
  public final double[] forceConstants;

  /**
   * StretchBendType Constructor.
   *
   * @param atomClasses    int[]
   * @param forceConstants double[]
   */
  public StretchBendType(int[] atomClasses, double[] forceConstants) {
    super(STRBND, sortKey(copyOf(atomClasses, 3)));
    // Sort the atom classes and force constants in tandem.
    if (atomClasses[0] > atomClasses[2]) {
      int temp = atomClasses[0];
      double f = forceConstants[0];
      atomClasses[0] = atomClasses[2];
      forceConstants[0] = forceConstants[1];
      atomClasses[2] = temp;
      forceConstants[1] = f;
    }
    this.atomClasses = atomClasses;
    this.forceConstants = forceConstants;
  }

  /**
   * average.
   *
   * @param stretchBendType1 a {@link ffx.potential.parameters.StretchBendType} object.
   * @param stretchBendType2 a {@link ffx.potential.parameters.StretchBendType} object.
   * @param atomClasses      an array of {@link int} objects.
   * @return a {@link ffx.potential.parameters.StretchBendType} object.
   */
  public static StretchBendType average(StretchBendType stretchBendType1,
                                        StretchBendType stretchBendType2, int[] atomClasses) {
    if (stretchBendType1 == null || stretchBendType2 == null || atomClasses == null) {
      return null;
    }
    int len = stretchBendType1.forceConstants.length;
    if (len != stretchBendType2.forceConstants.length) {
      return null;
    }
    double[] forceConstants = new double[len];
    for (int i = 0; i < len; i++) {
      forceConstants[i] =
          (stretchBendType1.forceConstants[i] + stretchBendType2.forceConstants[i]) / 2.0;
    }
    return new StretchBendType(atomClasses, forceConstants);
  }

  /**
   * Construct a StretchBendType from an input string.
   *
   * @param input  The overall input String.
   * @param tokens The input String tokenized.
   * @return a StretchBendType instance.
   */
  public static StretchBendType parse(String input, String[] tokens) {
    if (tokens.length < 6) {
      logger.log(Level.WARNING, "Invalid STRBND type:\n{0}", input);
    } else {
      try {
        int[] atomClasses = new int[3];
        atomClasses[0] = parseInt(tokens[1]);
        atomClasses[1] = parseInt(tokens[2]);
        atomClasses[2] = parseInt(tokens[3]);
        double[] forceConstants = new double[2];
        forceConstants[0] = parseDouble(tokens[4]);
        forceConstants[1] = parseDouble(tokens[5]);
        return new StretchBendType(atomClasses, forceConstants);
      } catch (NumberFormatException e) {
        String message = "Exception parsing STRBND type:\n" + input + "\n";
        logger.log(Level.SEVERE, message, e);
      }
    }
    return null;
  }

  /**
   * This method sorts the atom classes as: min, c[1], max
   *
   * @param c atomClasses
   * @return lookup key
   */
  public static String sortKey(int[] c) {
    if (c == null || c.length != 3) {
      return null;
    }
    if (c[0] > c[2]) {
      int temp = c[0];
      c[0] = c[2];
      c[2] = temp;
    }
    return c[0] + " " + c[1] + " " + c[2];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int compare(String key1, String key2) {
    String[] keys1 = key1.split(" ");
    String[] keys2 = key2.split(" ");
    int[] c1 = new int[3];
    int[] c2 = new int[3];
    for (int i = 0; i < 3; i++) {
      c1[i] = parseInt(keys1[i]);
      c2[i] = parseInt(keys2[i]);
    }
    if (c1[1] < c2[1]) {
      return -1;
    } else if (c1[1] > c2[1]) {
      return 1;
    } else if (c1[0] < c2[0]) {
      return -1;
    } else if (c1[0] > c2[0]) {
      return 1;
    } else if (c1[2] < c2[2]) {
      return -1;
    } else if (c1[2] > c2[2]) {
      return 1;
    }
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StretchBendType stretchBendType = (StretchBendType) o;
    return Arrays.equals(atomClasses, stretchBendType.atomClasses);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode() {
    return Arrays.hashCode(atomClasses);
  }

  /**
   * Increment the atom classes by a given value.
   *
   * @param increment The increment to apply to the atom classes.
   */
  public void incrementClasses(int increment) {
    for (int i = 0; i < atomClasses.length; i++) {
      atomClasses[i] += increment;
    }
    setKey(sortKey(atomClasses));
  }

  /**
   * Remap new atom classes to known internal ones.
   *
   * @param typeMap a lookup between new atom types and known atom types.
   * @return a {@link ffx.potential.parameters.StretchBendType} object.
   */
  public StretchBendType patchClasses(HashMap<AtomType, AtomType> typeMap) {
    int count = 0;
    int len = atomClasses.length;

    // Check if this StetchBendType contain 1 or 2 mapped atom classes.
    for (AtomType newType : typeMap.keySet()) {
      for (int atomClass : atomClasses) {
        if (atomClass == newType.atomClass) {
          count++;
        }
      }
    }

    // If found, create a new StetchBendType that bridges to known classes.
    if (count == 1 || count == 2) {
      int[] newClasses = Arrays.copyOf(atomClasses, len);
      for (AtomType newType : typeMap.keySet()) {
        for (int i = 0; i < len; i++) {
          if (atomClasses[i] == newType.atomClass) {
            AtomType knownType = typeMap.get(newType);
            newClasses[i] = knownType.atomClass;
          }
        }
      }

      return new StretchBendType(newClasses, forceConstants);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Nicely formatted Stretch-Bend string.
   */
  @Override
  public String toString() {
    return String.format("strbnd  %5d  %5d  %5d  %6.2f  %6.2f", atomClasses[0], atomClasses[1],
        atomClasses[2], forceConstants[0], forceConstants[1]);
  }

  /**
   * Create an AmoebaStretchBendForce element.
   *
   * @param doc        the Document instance.
   * @param forceField the ForceField to grab constants from.
   * @return the AmoebaStretchBendForce element.
   */
  public static Element getXMLForce(Document doc, ForceField forceField) {
    Map<String, StretchBendType> types = forceField.getStretchBendTypes();
    if (!types.values().isEmpty()) {
      Element node = doc.createElement("AmoebaStretchBendForce");
      node.setAttribute("stretchBendUnit", valueOf(forceField.getDouble("strbndunit", DEFAULT_STRBND_UNIT) * DEGREES_PER_RADIAN));
      for (StretchBendType stretchBendType : types.values()) {
        node.appendChild(stretchBendType.toXML(doc));
      }
      return node;
    }
    return null;
  }

  /**
   * Write StretchBendType to OpenMM XML format.
   */
  public Element toXML(Document doc) {
    Element node = doc.createElement("StretchBend");
    node.setAttribute("class1", format("%d", atomClasses[0]));
    node.setAttribute("class2", format("%d", atomClasses[1]));
    node.setAttribute("class3", format("%d", atomClasses[2]));
    // Convert kcal/mol/A-degrees to KJ/mol/nm-radians
    node.setAttribute("k1", format("%.17f", forceConstants[0] * KCAL_TO_KJ / (ANG_TO_NM * DEGREES_PER_RADIAN)));
    node.setAttribute("k2", format("%.17f", forceConstants[1] * KCAL_TO_KJ / (ANG_TO_NM * DEGREES_PER_RADIAN)));
    return node;
  }
}
